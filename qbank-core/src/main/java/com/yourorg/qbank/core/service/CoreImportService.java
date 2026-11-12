package com.yourorg.qbank.core.service;

import com.yourorg.qbank.core.event.ModuleImportedEvent;
import com.yourorg.qbank.core.exception.BadRequestException;
import com.yourorg.qbank.core.exception.ConflictException;
import com.yourorg.qbank.core.exception.NotFoundException;
import com.yourorg.qbank.core.importer.ColumnDetectionResult;
import com.yourorg.qbank.core.importer.ExcelImportPipeline;
import com.yourorg.qbank.core.importer.ImportIssue;
import com.yourorg.qbank.core.importer.ImportPreviewResult;
import com.yourorg.qbank.core.importer.ModuleShuffleAssigner;
import com.yourorg.qbank.core.importer.ParsedQuestionRow;
import com.yourorg.qbank.core.importer.RawSheetData;
import com.yourorg.qbank.core.importer.Validator;
import com.yourorg.qbank.core.model.EncryptedMcq;
import com.yourorg.qbank.core.model.EncryptedPayload;
import com.yourorg.qbank.core.model.ExamSession;
import com.yourorg.qbank.core.model.ImportPreviewRecord;
import com.yourorg.qbank.core.model.ModuleStatus;
import com.yourorg.qbank.core.model.PlainMcq;
import com.yourorg.qbank.core.model.Subject;
import com.yourorg.qbank.core.model.SubjectModule;
import com.yourorg.qbank.core.model.UserRole;
import com.yourorg.qbank.core.port.AuditPort;
import com.yourorg.qbank.core.port.AuthPort;
import com.yourorg.qbank.core.port.ClockPort;
import com.yourorg.qbank.core.port.CryptoPort;
import com.yourorg.qbank.core.port.EventPublisher;
import com.yourorg.qbank.core.port.ExamRepository;
import com.yourorg.qbank.core.port.ImportPreviewRepository;
import com.yourorg.qbank.core.port.JsonCodecPort;
import com.yourorg.qbank.core.port.McqRepository;
import com.yourorg.qbank.core.port.ModuleRepository;
import com.yourorg.qbank.core.port.RandomPort;
import com.yourorg.qbank.core.port.SpreadsheetPort;
import com.yourorg.qbank.core.port.SubjectRepository;
import com.yourorg.qbank.core.port.TransactionPort;
import com.yourorg.qbank.core.security.QBankAad;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HOD-only import workflow:
 * 1) Uploads workbook and stores encrypted preview rows strictly in original sheet order so HOD can fix errors.
 * 2) On confirm, validates zero errors, blocks if any FROZEN/LIVE session locks that module, auto-shuffles
 *    questions and options with remapped answer keys, assigns IDs in shuffled bank order, encrypts everything at rest,
 *    deletes the draft preview immediately, and returns ONLY counts and status (never question content).
 */
public class CoreImportService {

    private static final Pattern MODULE_SHEET_NAME = Pattern.compile("^(?i)(?:module|mod|m)[\\s_-]*([1-5])$");
    private static final int DEFAULT_MAX_UPLOAD_BYTES = 10 * 1024 * 1024;

    private final SubjectRepository subjectRepository;
    private final ModuleRepository moduleRepository;
    private final McqRepository mcqRepository;
    private final ExamRepository examRepository;
    private final ImportPreviewRepository previewRepository;
    private final SpreadsheetPort spreadsheetPort;
    private final CryptoPort cryptoPort;
    private final JsonCodecPort jsonCodec;
    private final RandomPort randomPort;
    private final ClockPort clockPort;
    private final TransactionPort transactionPort;
    private final AuditPort auditPort;
    private final EventPublisher eventPublisher;
    private final AuthGuard authGuard;
    private final ExcelImportPipeline importPipeline;
    private final ModuleShuffleAssigner shuffleAssigner = new ModuleShuffleAssigner();
    private final int maxUploadBytes;

    public CoreImportService(
            SubjectRepository subjectRepository,
            ModuleRepository moduleRepository,
            McqRepository mcqRepository,
            ExamRepository examRepository,
            ImportPreviewRepository previewRepository,
            SpreadsheetPort spreadsheetPort,
            CryptoPort cryptoPort,
            JsonCodecPort jsonCodec,
            RandomPort randomPort,
            ClockPort clockPort,
            TransactionPort transactionPort,
            AuditPort auditPort,
            EventPublisher eventPublisher,
            AuthPort authPort
    ) {
        this(subjectRepository, moduleRepository, mcqRepository, examRepository, previewRepository,
                spreadsheetPort, cryptoPort, jsonCodec, randomPort, clockPort, transactionPort,
                auditPort, eventPublisher, authPort, DEFAULT_MAX_UPLOAD_BYTES);
    }

    public CoreImportService(
            SubjectRepository subjectRepository,
            ModuleRepository moduleRepository,
            McqRepository mcqRepository,
            ExamRepository examRepository,
            ImportPreviewRepository previewRepository,
            SpreadsheetPort spreadsheetPort,
            CryptoPort cryptoPort,
            JsonCodecPort jsonCodec,
            RandomPort randomPort,
            ClockPort clockPort,
            TransactionPort transactionPort,
            AuditPort auditPort,
            EventPublisher eventPublisher,
            AuthPort authPort,
            int maxUploadBytes
    ) {
        this.subjectRepository = Objects.requireNonNull(subjectRepository);
        this.moduleRepository = Objects.requireNonNull(moduleRepository);
        this.mcqRepository = Objects.requireNonNull(mcqRepository);
        this.examRepository = Objects.requireNonNull(examRepository);
        this.previewRepository = Objects.requireNonNull(previewRepository);
        this.spreadsheetPort = Objects.requireNonNull(spreadsheetPort);
        this.cryptoPort = Objects.requireNonNull(cryptoPort);
        this.jsonCodec = Objects.requireNonNull(jsonCodec);
        this.randomPort = Objects.requireNonNull(randomPort);
        this.clockPort = Objects.requireNonNull(clockPort);
        this.transactionPort = Objects.requireNonNull(transactionPort);
        this.auditPort = Objects.requireNonNull(auditPort);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.authGuard = new AuthGuard(authPort);
        this.importPipeline = new ExcelImportPipeline(cryptoPort);
        this.maxUploadBytes = maxUploadBytes > 0 ? maxUploadBytes : DEFAULT_MAX_UPLOAD_BYTES;
    }

    public byte[] downloadBlankTemplate(boolean multiModule) {
        authGuard.requireRole(UserRole.HOD);
        return spreadsheetPort.createBlankTemplate(multiModule);
    }

    public ImportPreviewResult previewSingleModule(long subjectId, int moduleNumber, byte[] fileBytes, String filename) {
        AuthPort.AuthActor actor = authGuard.requireRole(UserRole.HOD);
        validateModuleNumber(moduleNumber);
        Subject subject = requireActiveSubject(subjectId);

        List<RawSheetData> sheets = spreadsheetPort.readWorkbook(fileBytes, filename, maxUploadBytes);
        if (sheets.isEmpty()) {
            throw new BadRequestException("Uploaded workbook contains no readable sheets");
        }
        RawSheetData targetSheet = pickSingleModuleSheet(sheets, moduleNumber);
        ImportPreviewResult result = analyzeAndStorePreview(subject, moduleNumber, filename, targetSheet, actor);

        auditPort.append(
                "IMPORT_PREVIEW_CREATED",
                actor.username(),
                AuthGuard.primaryRoleName(actor),
                "MODULE",
                subject.code() + "-M" + moduleNumber,
                jsonCodec.toJson(Map.of(
                        "previewId", result.previewId(),
                        "totalRows", result.totalRows(),
                        "validCount", result.validCount(),
                        "errorCount", result.errorCount(),
                        "warningCount", result.warningCount()
                ))
        );
        return result;
    }

    public List<ImportPreviewResult> previewMultiModule(long subjectId, byte[] fileBytes, String filename) {
        AuthPort.AuthActor actor = authGuard.requireRole(UserRole.HOD);
        Subject subject = requireActiveSubject(subjectId);

        List<RawSheetData> sheets = spreadsheetPort.readWorkbook(fileBytes, filename, maxUploadBytes);
        List<ImportPreviewResult> results = new ArrayList<>();
        for (RawSheetData sheet : sheets) {
            Integer modNum = parseModuleNumberFromSheetName(sheet.sheetName());
            if (modNum != null) {
                ImportPreviewResult result = analyzeAndStorePreview(subject, modNum, filename, sheet, actor);
                results.add(result);
            }
        }
        if (results.isEmpty()) {
            throw new BadRequestException("No sheets named 'Module 1'..'Module 5' found in workbook");
        }
        auditPort.append(
                "IMPORT_MULTIMODULE_PREVIEW_CREATED",
                actor.username(),
                AuthGuard.primaryRoleName(actor),
                "SUBJECT",
                subject.code(),
                jsonCodec.toJson(Map.of("sheetsDetected", results.size()))
        );
        return results;
    }

    public ImportPreviewResult getPreview(String previewId) {
        authGuard.requireRole(UserRole.HOD);
        ImportPreviewRecord rec = previewRepository.findById(previewId)
                .orElseThrow(() -> new NotFoundException("Preview session not found or already confirmed/discarded"));
        return decryptPreview(rec);
    }

    public ImportPreviewResult updatePreviewRow(
            String previewId,
            int sourceRow,
            String questionText,
            List<String> options,
            Integer correctIndex,
            boolean excluded
    ) {
        AuthPort.AuthActor actor = authGuard.requireRole(UserRole.HOD);
        ImportPreviewRecord rec = previewRepository.findById(previewId)
                .orElseThrow(() -> new NotFoundException("Preview session not found or already confirmed/discarded"));
        ImportPreviewResult current = decryptPreview(rec);
        Subject subject = requireActiveSubject(current.subjectId());

        List<ParsedQuestionRow> updatedRows = new ArrayList<>(current.rows());
        ParsedQuestionRow match = updatedRows.stream()
                .filter(r -> r.getSourceRow() == sourceRow)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Row " + sourceRow + " not found in preview"));

        match.setExcluded(excluded);
        if (!excluded) {
            if (questionText != null) {
                match.setQuestionText(questionText);
            }
            if (options != null) {
                match.setOptions(options);
            }
            if (correctIndex != null) {
                match.setCorrectIndex(correctIndex);
                match.setRawAnswerInput(String.valueOf((char) ('A' + Math.max(0, correctIndex))));
                match.setAnswerInferredFromText(false);
            }
        }

        Validator.ValidationSummary summary = importPipeline.revalidateRows(
                current.sheetName(),
                updatedRows,
                current.columnDetection(),
                subject.expectedQuestionsPerModule()
        );
        ImportPreviewResult refreshed = new ImportPreviewResult(
                current.previewId(),
                current.subjectId(),
                current.subjectCode(),
                current.moduleNumber(),
                current.filename(),
                current.sheetName(),
                current.columnDetection(),
                updatedRows.size(),
                summary.validCount(),
                summary.warningCount(),
                summary.errorCount(),
                summary.duplicateSkippedCount(),
                summary.excludedCount(),
                subject.expectedQuestionsPerModule(),
                summary.validCount() > 0 && summary.errorCount() == 0,
                summary.issues(),
                updatedRows
        );
        saveEncryptedPreview(rec.id(), rec.subjectId(), rec.moduleNumber(), rec.filename(), rec.sheetName(),
                refreshed, actor.username(), rec.createdAt());
        return refreshed;
    }

    public ConfirmImportResult confirmPreview(String previewId) {
        AuthPort.AuthActor actor = authGuard.requireRole(UserRole.HOD);
        ImportPreviewRecord rec = previewRepository.findById(previewId)
                .orElseThrow(() -> new NotFoundException("Preview session not found or already confirmed"));
        ImportPreviewResult preview = decryptPreview(rec);
        Subject subject = requireActiveSubject(preview.subjectId());

        Validator.ValidationSummary summary = importPipeline.revalidateRows(
                preview.sheetName(),
                preview.rows(),
                preview.columnDetection(),
                subject.expectedQuestionsPerModule()
        );
        if (summary.errorCount() > 0) {
            throw new BadRequestException("Cannot confirm import with " + summary.errorCount() + " row error(s). Fix or exclude bad rows first.");
        }
        if (summary.validCount() == 0) {
            throw new BadRequestException("Cannot confirm import with zero valid questions");
        }

        List<ExamSession> blocking = examRepository.findFrozenOrLiveSessionsUsingModule(subject.id(), preview.moduleNumber());
        if (!blocking.isEmpty()) {
            throw new ConflictException(
                    "Module " + preview.moduleNumber() + " is locked by a FROZEN or LIVE exam session (" + blocking.get(0).sessionSlot() + ")");
        }

        Instant now = clockPort.now();
        return transactionPort.inTransaction(() -> {
            List<PlainMcq> shuffledBank = shuffleAssigner.shuffleAndAssignIds(
                    subject.id(),
                    subject.code(),
                    preview.moduleNumber(),
                    preview.rows(),
                    randomPort
            );

            List<EncryptedMcq> encryptedMcqs = new ArrayList<>(shuffledBank.size());
            for (PlainMcq mcq : shuffledBank) {
                EncryptedPayload textEnc = cryptoPort.encrypt(mcq.text(), QBankAad.mcqText(subject.id(), preview.moduleNumber(), mcq.id()));
                EncryptedPayload optsEnc = cryptoPort.encrypt(jsonCodec.toJson(mcq.options()), QBankAad.mcqOptions(subject.id(), preview.moduleNumber(), mcq.id()));
                EncryptedPayload ansEnc = cryptoPort.encrypt(Integer.toString(mcq.correctIndex()), QBankAad.mcqCorrectIndex(subject.id(), preview.moduleNumber(), mcq.id()));
                String hash = cryptoPort.sha256Hex(mcq.text() + "\n" + String.join("\n", mcq.options()));
                encryptedMcqs.add(new EncryptedMcq(
                        mcq.id(),
                        subject.id(),
                        preview.moduleNumber(),
                        mcq.seq(),
                        textEnc,
                        optsEnc,
                        ansEnc,
                        hash,
                        mcq.sourceRow(),
                        now
                ));
            }

            mcqRepository.replaceModuleMcqs(subject.id(), preview.moduleNumber(), encryptedMcqs);

            SubjectModule existingMod = moduleRepository.findBySubjectAndNumber(subject.id(), preview.moduleNumber()).orElse(null);
            moduleRepository.save(new SubjectModule(
                    existingMod == null ? null : existingMod.id(),
                    subject.id(),
                    subject.code(),
                    preview.moduleNumber(),
                    ModuleStatus.READY,
                    encryptedMcqs.size(),
                    preview.filename(),
                    actor.username(),
                    now,
                    0,
                    summary.warningCount()
            ));

            // Immediately delete preview so HOD can no longer inspect question text or learn shuffled order
            previewRepository.deleteById(previewId);

            auditPort.append(
                    "MODULE_IMPORT_CONFIRMED",
                    actor.username(),
                    AuthGuard.primaryRoleName(actor),
                    "MODULE",
                    subject.code() + "-M" + preview.moduleNumber(),
                    jsonCodec.toJson(Map.of(
                            "questionCount", encryptedMcqs.size(),
                            "warningCount", summary.warningCount(),
                            "shuffledOnConfirm", true
                    ))
            );
            eventPublisher.publish(new ModuleImportedEvent(
                    subject.id(),
                    subject.code(),
                    preview.moduleNumber(),
                    encryptedMcqs.size(),
                    actor.username(),
                    now
            ));

            return new ConfirmImportResult(
                    subject.id(),
                    subject.code(),
                    preview.moduleNumber(),
                    ModuleStatus.READY,
                    encryptedMcqs.size(),
                    summary.warningCount()
            );
        });
    }

    public void discardPreview(String previewId) {
        authGuard.requireRole(UserRole.HOD);
        previewRepository.deleteById(previewId);
    }

    private ImportPreviewResult analyzeAndStorePreview(
            Subject subject,
            int moduleNumber,
            String filename,
            RawSheetData sheet,
            AuthPort.AuthActor actor
    ) {
        String previewId = randomPort.randomUuid();
        ImportPreviewResult result = importPipeline.preview(
                previewId,
                subject.id(),
                subject.code(),
                moduleNumber,
                filename == null ? "upload.xlsx" : filename,
                sheet,
                subject.expectedQuestionsPerModule()
        );
        Instant now = clockPort.now();
        saveEncryptedPreview(previewId, subject.id(), moduleNumber, result.filename(), result.sheetName(), result, actor.username(), now);
        return result;
    }

    private void saveEncryptedPreview(
            String previewId,
            Long subjectId,
            int moduleNumber,
            String filename,
            String sheetName,
            ImportPreviewResult result,
            String createdBy,
            Instant createdAt
    ) {
        String json = jsonCodec.toJson(result);
        EncryptedPayload payloadEnc = cryptoPort.encrypt(json, QBankAad.previewPayload(previewId));
        previewRepository.save(new ImportPreviewRecord(
                previewId, subjectId, moduleNumber, filename, sheetName, payloadEnc, createdBy, createdAt
        ));
    }

    private ImportPreviewResult decryptPreview(ImportPreviewRecord rec) {
        String json = cryptoPort.decrypt(rec.payloadEnc(), QBankAad.previewPayload(rec.id()));
        return jsonCodec.fromJson(json, ImportPreviewResult.class);
    }

    private Subject requireActiveSubject(long subjectId) {
        Subject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new NotFoundException("Subject not found: " + subjectId));
        if (subject.archived()) {
            throw new ConflictException("Subject " + subject.code() + " is archived");
        }
        return subject;
    }

    private static RawSheetData pickSingleModuleSheet(List<RawSheetData> sheets, int targetModule) {
        for (RawSheetData s : sheets) {
            Integer parsed = parseModuleNumberFromSheetName(s.sheetName());
            if (parsed != null && parsed == targetModule) {
                return s;
            }
        }
        return sheets.get(0);
    }

    private static Integer parseModuleNumberFromSheetName(String sheetName) {
        if (sheetName == null) {
            return null;
        }
        Matcher m = MODULE_SHEET_NAME.matcher(sheetName.trim());
        if (m.matches()) {
            return Integer.parseInt(m.group(1));
        }
        return null;
    }

    private static void validateModuleNumber(int moduleNumber) {
        if (moduleNumber < 1 || moduleNumber > 5) {
            throw new BadRequestException("Module number must be between 1 and 5");
        }
    }

    public record ConfirmImportResult(
            Long subjectId,
            String subjectCode,
            int moduleNumber,
            ModuleStatus status,
            int questionCount,
            int warningCount
    ) {
    }
}
