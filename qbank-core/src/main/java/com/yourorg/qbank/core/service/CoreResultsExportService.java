package com.yourorg.qbank.core.service;

import com.yourorg.qbank.core.exception.ConflictException;
import com.yourorg.qbank.core.exception.NotFoundException;
import com.yourorg.qbank.core.generator.PaperSetVersionGenerator;
import com.yourorg.qbank.core.model.AnswerKeyEntry;
import com.yourorg.qbank.core.model.Exam;
import com.yourorg.qbank.core.model.ExamSession;
import com.yourorg.qbank.core.model.ExamStudent;
import com.yourorg.qbank.core.model.SessionStatus;
import com.yourorg.qbank.core.model.StudentResponseRecord;
import com.yourorg.qbank.core.model.StudentScoreResult;
import com.yourorg.qbank.core.model.UserRole;
import com.yourorg.qbank.core.port.AuditPort;
import com.yourorg.qbank.core.port.AuthPort;
import com.yourorg.qbank.core.port.CryptoPort;
import com.yourorg.qbank.core.port.ExamRepository;
import com.yourorg.qbank.core.port.ExamStudentRepository;
import com.yourorg.qbank.core.port.JsonCodecPort;
import com.yourorg.qbank.core.port.SpreadsheetPort;
import com.yourorg.qbank.core.port.StudentResponseRepository;
import com.yourorg.qbank.core.scoring.DeterministicScorer;
import com.yourorg.qbank.core.security.FormulaSanitizer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Deterministic scoring and sanitized CSV/XLSX score-sheet exporter for ENDED exam sessions.
 * Accessible to ADMIN and COE; NEVER exposes question stems, MCQ IDs, options, or answer keys.
 */
public class CoreResultsExportService {

    private final ExamRepository examRepository;
    private final ExamStudentRepository studentRepository;
    private final StudentResponseRepository responseRepository;
    private final DefaultFrozenPaperProvider frozenPaperProvider;
    private final SpreadsheetPort spreadsheetPort;
    private final CryptoPort cryptoPort;
    private final JsonCodecPort jsonCodec;
    private final AuditPort auditPort;
    private final AuthGuard authGuard;

    public CoreResultsExportService(
            ExamRepository examRepository,
            ExamStudentRepository studentRepository,
            StudentResponseRepository responseRepository,
            DefaultFrozenPaperProvider frozenPaperProvider,
            SpreadsheetPort spreadsheetPort,
            CryptoPort cryptoPort,
            JsonCodecPort jsonCodec,
            AuditPort auditPort,
            AuthPort authPort
    ) {
        this.examRepository = Objects.requireNonNull(examRepository);
        this.studentRepository = Objects.requireNonNull(studentRepository);
        this.responseRepository = Objects.requireNonNull(responseRepository);
        this.frozenPaperProvider = Objects.requireNonNull(frozenPaperProvider);
        this.spreadsheetPort = Objects.requireNonNull(spreadsheetPort);
        this.cryptoPort = Objects.requireNonNull(cryptoPort);
        this.jsonCodec = Objects.requireNonNull(jsonCodec);
        this.auditPort = Objects.requireNonNull(auditPort);
        this.authGuard = new AuthGuard(authPort);
    }

    public SessionResultsExport exportSessionResults(long sessionId) {
        AuthPort.AuthActor actor = authGuard.requireAnyRole(UserRole.ADMIN, UserRole.COE);
        ExamSession session = examRepository.findSessionById(sessionId)
                .orElseThrow(() -> new NotFoundException("Exam session not found: " + sessionId));
        if (session.status() != SessionStatus.ENDED) {
            throw new ConflictException("Results can only be exported after the exam session has ENDED (status: " + session.status() + ")");
        }
        Exam exam = examRepository.findExamById(session.examId())
                .orElseThrow(() -> new NotFoundException("Exam not found: " + session.examId()));

        Map<String, List<AnswerKeyEntry>> verifiedKeys = new LinkedHashMap<>();
        for (String label : PaperSetVersionGenerator.VERSION_LABELS) {
            verifiedKeys.put(label, frozenPaperProvider.decryptVerifiedKeyForScoring(sessionId, label));
        }

        List<ExamStudent> students = studentRepository.findBySessionId(sessionId).stream()
                .sorted(Comparator.comparing(ExamStudent::rollNumber))
                .toList();

        Map<String, List<StudentResponseRecord>> responsesByRoll = responseRepository.findBySessionId(sessionId).stream()
                .collect(Collectors.groupingBy(StudentResponseRecord::rollNumber));

        List<StudentScoreResult> scores = new ArrayList<>(students.size());
        for (ExamStudent st : students) {
            List<AnswerKeyEntry> key = verifiedKeys.get(st.assignedVersion());
            List<StudentResponseRecord> resps = responsesByRoll.getOrDefault(st.rollNumber(), List.of());
            scores.add(DeterministicScorer.score(st, exam.marksPerQuestion(), key, resps));
        }

        StringBuilder csvBuilder = new StringBuilder();
        List<List<String>> sheetRows = new ArrayList<>(scores.size() + 1);
        List<String> header = List.of(
                "Roll Number", "Full Name", "Room", "Version",
                "Total Questions", "Correct", "Wrong", "Unanswered", "Marks Awarded", "Max Marks"
        );
        sheetRows.add(header);
        appendCsvRow(csvBuilder, header);

        for (StudentScoreResult r : scores) {
            List<String> row = List.of(
                    r.rollNumber(),
                    r.fullName(),
                    r.roomCode(),
                    r.assignedVersion(),
                    Integer.toString(r.totalQuestions()),
                    Integer.toString(r.correctCount()),
                    Integer.toString(r.wrongCount()),
                    Integer.toString(r.unansweredCount()),
                    Integer.toString(r.marksAwarded()),
                    Integer.toString(r.maxMarks())
            );
            sheetRows.add(row);
            appendCsvRow(csvBuilder, row);
        }

        byte[] csvBytes = csvBuilder.toString().getBytes(StandardCharsets.UTF_8);
        String csvSha256 = cryptoPort.sha256BytesHex(csvBytes);
        byte[] xlsxBytes = spreadsheetPort.createSanitizedWorkbook("Results", sheetRows);
        String xlsxSha256 = cryptoPort.sha256BytesHex(xlsxBytes);

        auditPort.append(
                "RESULTS_EXPORTED",
                actor.username(),
                AuthGuard.primaryRoleName(actor),
                "SESSION",
                String.valueOf(sessionId),
                jsonCodec.toJson(Map.of(
                        "studentCount", scores.size(),
                        "csvSha256", csvSha256,
                        "xlsxSha256", xlsxSha256
                ))
        );

        return new SessionResultsExport(
                exam,
                session,
                scores,
                csvBytes,
                xlsxBytes,
                csvSha256,
                xlsxSha256
        );
    }

    private static void appendCsvRow(StringBuilder sb, List<String> columns) {
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(FormulaSanitizer.escapeCsvField(columns.get(i)));
        }
        sb.append("\r\n");
    }

    public record SessionResultsExport(
            Exam exam,
            ExamSession session,
            List<StudentScoreResult> results,
            byte[] csvBytes,
            byte[] xlsxBytes,
            String csvSha256,
            String xlsxSha256
    ) {
    }
}
