package com.yourorg.qbank.core.service;

import com.yourorg.qbank.core.event.PaperFrozenEvent;
import com.yourorg.qbank.core.event.PaperGeneratedEvent;
import com.yourorg.qbank.core.exception.BadRequestException;
import com.yourorg.qbank.core.exception.ConflictException;
import com.yourorg.qbank.core.exception.ForbiddenException;
import com.yourorg.qbank.core.exception.NotFoundException;
import com.yourorg.qbank.core.generator.BlueprintCalculator;
import com.yourorg.qbank.core.generator.BlueprintResult;
import com.yourorg.qbank.core.generator.PaperSetVersionGenerator;
import com.yourorg.qbank.core.model.AnswerKeyEntry;
import com.yourorg.qbank.core.model.EncryptedMcq;
import com.yourorg.qbank.core.model.EncryptedPayload;
import com.yourorg.qbank.core.model.Exam;
import com.yourorg.qbank.core.model.ExamSession;
import com.yourorg.qbank.core.model.ExamSessionSlot;
import com.yourorg.qbank.core.model.ExamType;
import com.yourorg.qbank.core.model.GeneratedSetData;
import com.yourorg.qbank.core.model.GeneratedVersionData;
import com.yourorg.qbank.core.model.ModuleStatus;
import com.yourorg.qbank.core.model.ModuleUnit;
import com.yourorg.qbank.core.model.PaperSetStatus;
import com.yourorg.qbank.core.model.PaperSetStored;
import com.yourorg.qbank.core.model.PaperVersionStored;
import com.yourorg.qbank.core.model.PlainMcq;
import com.yourorg.qbank.core.model.SessionStatus;
import com.yourorg.qbank.core.model.SetSummaryCard;
import com.yourorg.qbank.core.model.Subject;
import com.yourorg.qbank.core.model.SubjectModule;
import com.yourorg.qbank.core.model.UserRole;
import com.yourorg.qbank.core.port.AuditPort;
import com.yourorg.qbank.core.port.AuthPort;
import com.yourorg.qbank.core.port.ClockPort;
import com.yourorg.qbank.core.port.CryptoPort;
import com.yourorg.qbank.core.port.EventPublisher;
import com.yourorg.qbank.core.port.ExamRepository;
import com.yourorg.qbank.core.port.JsonCodecPort;
import com.yourorg.qbank.core.port.McqRepository;
import com.yourorg.qbank.core.port.ModuleRepository;
import com.yourorg.qbank.core.port.RandomPort;
import com.yourorg.qbank.core.port.SubjectRepository;
import com.yourorg.qbank.core.port.TransactionPort;
import com.yourorg.qbank.core.security.PaperIntegrityCalculator;
import com.yourorg.qbank.core.security.QBankAad;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * COE paper generation, dual-session slicing (Morning + optional/late Afternoon), approval/freezing,
 * and start/stop lifecycle service. Strictly omits ALL question text, options, IDs, and answer keys
 * from every return value.
 */
public class CoreExamPaperService {

    private final SubjectRepository subjectRepository;
    private final ModuleRepository moduleRepository;
    private final McqRepository mcqRepository;
    private final ExamRepository examRepository;
    private final CryptoPort cryptoPort;
    private final JsonCodecPort jsonCodec;
    private final RandomPort randomPort;
    private final ClockPort clockPort;
    private final TransactionPort transactionPort;
    private final AuditPort auditPort;
    private final EventPublisher eventPublisher;
    private final AuthGuard authGuard;
    private final BlueprintCalculator blueprintCalculator = new BlueprintCalculator();
    private final PaperSetVersionGenerator generator = new PaperSetVersionGenerator();
    private final DefaultFrozenPaperProvider frozenPaperProvider;

    public CoreExamPaperService(
            SubjectRepository subjectRepository,
            ModuleRepository moduleRepository,
            McqRepository mcqRepository,
            ExamRepository examRepository,
            CryptoPort cryptoPort,
            JsonCodecPort jsonCodec,
            RandomPort randomPort,
            ClockPort clockPort,
            TransactionPort transactionPort,
            AuditPort auditPort,
            EventPublisher eventPublisher,
            AuthPort authPort,
            DefaultFrozenPaperProvider frozenPaperProvider
    ) {
        this.subjectRepository = Objects.requireNonNull(subjectRepository);
        this.moduleRepository = Objects.requireNonNull(moduleRepository);
        this.mcqRepository = Objects.requireNonNull(mcqRepository);
        this.examRepository = Objects.requireNonNull(examRepository);
        this.cryptoPort = Objects.requireNonNull(cryptoPort);
        this.jsonCodec = Objects.requireNonNull(jsonCodec);
        this.randomPort = Objects.requireNonNull(randomPort);
        this.clockPort = Objects.requireNonNull(clockPort);
        this.transactionPort = Objects.requireNonNull(transactionPort);
        this.auditPort = Objects.requireNonNull(auditPort);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.authGuard = new AuthGuard(authPort);
        this.frozenPaperProvider = frozenPaperProvider;
    }

    public BlueprintResult calculateBlueprint(long subjectId, ExamType examType, List<Integer> requestedModules, int rotationOffset) {
        authGuard.requireRole(UserRole.COE);
        Subject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new NotFoundException("Subject not found: " + subjectId));
        return blueprintCalculator.calculate(examType, requestedModules, subject.marksPerQuestion(), rotationOffset);
    }

    public GenerateExamResult generateExam(
            long subjectId,
            String rawTitle,
            ExamType examType,
            List<Integer> requestedModules,
            LocalDate examDate,
            int rotationOffset,
            boolean includeAfternoon,
            Instant morningStartAt,
            Instant morningEndAt,
            Instant afternoonStartAt,
            Instant afternoonEndAt
    ) {
        AuthPort.AuthActor actor = authGuard.requireRole(UserRole.COE);
        Subject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new NotFoundException("Subject not found: " + subjectId));
        if (subject.archived()) {
            throw new ConflictException("Subject is archived: " + subject.code());
        }
        if (examDate == null) {
            throw new BadRequestException("Exam date is required");
        }
        validateWindow("Morning", morningStartAt, morningEndAt);
        if (includeAfternoon) {
            validateWindow("Afternoon", afternoonStartAt, afternoonEndAt);
        }

        BlueprintResult blueprint = blueprintCalculator.calculate(
                examType, requestedModules, subject.marksPerQuestion(), rotationOffset
        );
        verifySelectedModulesReady(subject, blueprint.selectedModules());

        String title = (rawTitle == null || rawTitle.isBlank())
                ? subject.code() + " " + examType.name() + " (" + examDate + ")"
                : rawTitle.trim();

        Map<Integer, List<PlainMcq>> decryptedPool = loadDecryptedModulePools(subject.id(), blueprint.selectedModules());
        long seed = randomPort.nextLong();
        PaperSetVersionGenerator.DualSessionGeneration dual = generator.generateDualSessions(
                decryptedPool, blueprint, seed, includeAfternoon, randomPort
        );
        Instant now = clockPort.now();

        return transactionPort.inTransaction(() -> {
            Exam savedExam = examRepository.saveExam(new Exam(
                    null,
                    subject.id(),
                    subject.code(),
                    subject.name(),
                    title,
                    examType,
                    examDate,
                    blueprint.marksPerQuestion(),
                    blueprint.totalMarks(),
                    blueprint.totalQuestions(),
                    blueprint.selectedModules(),
                    blueprint.moduleCounts(),
                    blueprint.rotationOffset(),
                    seed,
                    actor.username(),
                    now
            ));

            List<SessionWithSetCards> createdSessions = new ArrayList<>();
            createdSessions.add(persistGeneratedSession(
                    savedExam, ExamSessionSlot.MORNING, dual.morningSets(), morningStartAt, morningEndAt, actor, now));

            if (includeAfternoon) {
                createdSessions.add(persistGeneratedSession(
                        savedExam, ExamSessionSlot.AFTERNOON, dual.afternoonSets(), afternoonStartAt, afternoonEndAt, actor, now));
            }

            return new GenerateExamResult(savedExam, createdSessions);
        });
    }

    public SessionWithSetCards addAfternoonSession(long examId, Instant afternoonStartAt, Instant afternoonEndAt) {
        AuthPort.AuthActor actor = authGuard.requireRole(UserRole.COE);
        validateWindow("Afternoon", afternoonStartAt, afternoonEndAt);

        Exam exam = examRepository.findExamById(examId)
                .orElseThrow(() -> new NotFoundException("Exam not found: " + examId));
        Subject subject = subjectRepository.findById(exam.subjectId())
                .orElseThrow(() -> new NotFoundException("Subject not found: " + exam.subjectId()));

        List<ExamSession> existingSessions = examRepository.findSessionsByExamId(examId);
        boolean hasAfternoon = existingSessions.stream().anyMatch(s -> s.sessionSlot() == ExamSessionSlot.AFTERNOON);
        if (hasAfternoon) {
            throw new ConflictException("Exam already has an AFTERNOON session");
        }
        ExamSession morning = existingSessions.stream()
                .filter(s -> s.sessionSlot() == ExamSessionSlot.MORNING)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("MORNING session missing for exam " + examId));

        verifySelectedModulesReady(subject, exam.selectedModules());
        Set<String> excludedQuestionIds = examRepository.findQuestionIdsUsedInSession(morning.id());
        BlueprintResult blueprint = blueprintCalculator.calculate(
                exam.examType(), exam.selectedModules(), exam.marksPerQuestion(), exam.rotationUsed()
        );

        Map<Integer, List<PlainMcq>> pool = loadDecryptedModulePools(exam.subjectId(), exam.selectedModules());
        long aftSeed = randomPort.nextLong();
        List<GeneratedSetData> aftSets = generator.generateLateAfternoonSession(
                pool, excludedQuestionIds, blueprint, aftSeed, randomPort
        );
        Instant now = clockPort.now();

        return transactionPort.inTransaction(() ->
                persistGeneratedSession(exam, ExamSessionSlot.AFTERNOON, aftSets, afternoonStartAt, afternoonEndAt, actor, now));
    }

    public List<SetSummaryCard> listSetCards(long sessionId) {
        authGuard.requireRole(UserRole.COE);
        examRepository.findSessionById(sessionId)
                .orElseThrow(() -> new NotFoundException("Exam session not found: " + sessionId));
        return examRepository.findSetsBySessionId(sessionId).stream()
                .map(s -> new SetSummaryCard(s.setNumber(), s.questionCount(), s.status()))
                .toList();
    }

    public ApprovalConfirmation getApprovalConfirmation(long sessionId, int setNumber) {
        authGuard.requireRole(UserRole.COE);
        ExamSession session = examRepository.findSessionById(sessionId)
                .orElseThrow(() -> new NotFoundException("Exam session not found: " + sessionId));
        Exam exam = examRepository.findExamById(session.examId())
                .orElseThrow(() -> new NotFoundException("Exam not found: " + session.examId()));
        PaperSetStored targetSet = findSetByNumber(sessionId, setNumber);

        return new ApprovalConfirmation(
                exam.id(),
                session.id(),
                exam.title(),
                exam.subjectCode(),
                exam.subjectName(),
                exam.examType(),
                session.sessionSlot(),
                exam.examDate(),
                targetSet.setNumber(),
                targetSet.questionCount(),
                exam.totalMarks(),
                session.scheduledStartAt(),
                session.scheduledEndAt(),
                session.version()
        );
    }

    public ExamSession approveAndFreezeSession(
            long sessionId,
            int setNumber,
            int expectedVersion,
            Instant scheduledStartAt,
            Instant scheduledEndAt
    ) {
        AuthPort.AuthActor actor = authGuard.requireRole(UserRole.COE);
        if (setNumber < 1 || setNumber > PaperSetVersionGenerator.SETS_PER_SESSION) {
            throw new BadRequestException("Set number must be between 1 and 4");
        }

        ExamSession session = examRepository.findSessionById(sessionId)
                .orElseThrow(() -> new NotFoundException("Exam session not found: " + sessionId));
        if (session.status() != SessionStatus.DRAFT) {
            throw new ConflictException("Only DRAFT sessions can be approved and frozen (current status: " + session.status() + ")");
        }
        Instant effStart = scheduledStartAt != null ? scheduledStartAt : session.scheduledStartAt();
        Instant effEnd = scheduledEndAt != null ? scheduledEndAt : session.scheduledEndAt();
        validateWindow(session.sessionSlot().name(), effStart, effEnd);

        Exam exam = examRepository.findExamById(session.examId())
                .orElseThrow(() -> new NotFoundException("Exam not found: " + session.examId()));
        List<PaperSetStored> allSets = examRepository.findSetsBySessionId(sessionId);
        PaperSetStored chosenSet = allSets.stream()
                .filter(s -> s.setNumber() == setNumber)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Set " + setNumber + " not found for session " + sessionId));

        List<PaperVersionStored> versions = examRepository.findVersionsBySetId(chosenSet.id());
        if (versions.size() != PaperSetVersionGenerator.VERSION_LABELS.size()) {
            throw new IllegalStateException("Corrupt set versions count for set " + chosenSet.id());
        }
        String sessionContentSha256 = PaperIntegrityCalculator.computeSessionSetSha256(cryptoPort, setNumber, versions);
        Instant now = clockPort.now();

        return transactionPort.inTransaction(() -> {
            // Update window if COE refined start/end during approval step
            ExamSession refreshed = examRepository.saveSession(new ExamSession(
                    session.id(),
                    session.examId(),
                    session.sessionSlot(),
                    session.status(),
                    session.chosenSetNumber(),
                    effStart,
                    effEnd,
                    session.actualStartedAt(),
                    session.actualEndedAt(),
                    session.startedBy(),
                    session.endedBy(),
                    session.frozenBy(),
                    session.frozenAt(),
                    session.contentSha256(),
                    session.version()
            ));

            boolean updated = examRepository.updateSessionStatusWithVersion(
                    sessionId,
                    expectedVersion,
                    SessionStatus.FROZEN,
                    setNumber,
                    null,
                    null,
                    null,
                    null,
                    actor.username(),
                    now,
                    sessionContentSha256
            );
            if (!updated) {
                throw new ConflictException("Session was modified concurrently; please refresh and retry");
            }

            for (PaperSetStored ps : allSets) {
                PaperSetStatus targetStatus = (ps.setNumber() == setNumber)
                        ? PaperSetStatus.SELECTED
                        : PaperSetStatus.ARCHIVED;
                examRepository.updatePaperSetStatus(ps.id(), targetStatus);
            }

            ExamSession frozen = examRepository.findSessionById(sessionId)
                    .orElseThrow(() -> new IllegalStateException("Session vanished after freeze"));

            auditPort.append(
                    "PAPER_APPROVED_AND_FROZEN",
                    actor.username(),
                    AuthGuard.primaryRoleName(actor),
                    "SESSION",
                    String.valueOf(sessionId),
                    jsonCodec.toJson(Map.of(
                            "examId", exam.id(),
                            "subjectCode", exam.subjectCode(),
                            "slot", frozen.sessionSlot().name(),
                            "chosenSetNumber", setNumber,
                            "contentSha256", sessionContentSha256
                    ))
            );
            eventPublisher.publish(new PaperFrozenEvent(
                    exam.id(),
                    frozen.id(),
                    exam.subjectCode(),
                    frozen.sessionSlot(),
                    setNumber,
                    effStart,
                    effEnd,
                    actor.username(),
                    now
            ));
            return frozen;
        });
    }

    public ExamSession startSession(long sessionId) {
        AuthPort.AuthActor actor = authGuard.requireRole(UserRole.COE);
        ExamSession session = examRepository.findSessionById(sessionId)
                .orElseThrow(() -> new NotFoundException("Exam session not found: " + sessionId));
        if (session.status() != SessionStatus.FROZEN) {
            throw new ConflictException("Session must be FROZEN before starting (current status: " + session.status() + ")");
        }
        Instant now = clockPort.now();
        if (now.isBefore(session.scheduledStartAt())) {
            throw new ForbiddenException("Cannot start session before scheduled start time: " + session.scheduledStartAt());
        }
        if (now.isAfter(session.scheduledEndAt())) {
            throw new ForbiddenException("Scheduled exam window already passed: " + session.scheduledEndAt());
        }

        boolean ok = examRepository.updateSessionStatusWithVersion(
                sessionId,
                session.version(),
                SessionStatus.LIVE,
                session.chosenSetNumber(),
                now,
                null,
                actor.username(),
                null,
                session.frozenBy(),
                session.frozenAt(),
                session.contentSha256()
        );
        if (!ok) {
            throw new ConflictException("Session was modified concurrently; refresh and retry");
        }
        ExamSession live = examRepository.findSessionById(sessionId).orElseThrow();
        auditPort.append(
                "SESSION_STARTED",
                actor.username(),
                AuthGuard.primaryRoleName(actor),
                "SESSION",
                String.valueOf(sessionId),
                jsonCodec.toJson(Map.of("slot", live.sessionSlot().name()))
        );
        return live;
    }

    public ExamSession stopSession(long sessionId) {
        AuthPort.AuthActor actor = authGuard.requireRole(UserRole.COE);
        ExamSession session = examRepository.findSessionById(sessionId)
                .orElseThrow(() -> new NotFoundException("Exam session not found: " + sessionId));
        if (session.status() != SessionStatus.LIVE && session.status() != SessionStatus.FROZEN) {
            throw new ConflictException("Session cannot be stopped from status " + session.status());
        }
        Instant now = clockPort.now();
        boolean ok = examRepository.updateSessionStatusWithVersion(
                sessionId,
                session.version(),
                SessionStatus.ENDED,
                session.chosenSetNumber(),
                session.actualStartedAt(),
                now,
                session.startedBy(),
                actor.username(),
                session.frozenBy(),
                session.frozenAt(),
                session.contentSha256()
        );
        if (!ok) {
            throw new ConflictException("Session was modified concurrently; refresh and retry");
        }
        if (frozenPaperProvider != null) {
            frozenPaperProvider.evictSession(sessionId);
        }
        ExamSession ended = examRepository.findSessionById(sessionId).orElseThrow();
        auditPort.append(
                "SESSION_STOPPED",
                actor.username(),
                AuthGuard.primaryRoleName(actor),
                "SESSION",
                String.valueOf(sessionId),
                jsonCodec.toJson(Map.of("slot", ended.sessionSlot().name()))
        );
        return ended;
    }

    public List<Exam> listExams() {
        authGuard.requireAnyRole(UserRole.ADMIN, UserRole.COE, UserRole.INVIGILATOR);
        return examRepository.findAllExams();
    }

    public Exam getExam(long examId) {
        authGuard.requireAnyRole(UserRole.ADMIN, UserRole.COE, UserRole.INVIGILATOR);
        return examRepository.findExamById(examId)
                .orElseThrow(() -> new NotFoundException("Exam not found: " + examId));
    }

    public List<ExamSession> listSessionsForExam(long examId) {
        authGuard.requireAnyRole(UserRole.ADMIN, UserRole.COE, UserRole.INVIGILATOR);
        return examRepository.findSessionsByExamId(examId);
    }

    public List<ExamSession> listAllSessions() {
        authGuard.requireAnyRole(UserRole.ADMIN, UserRole.COE, UserRole.INVIGILATOR);
        return examRepository.findAllSessions();
    }

    private SessionWithSetCards persistGeneratedSession(
            Exam exam,
            ExamSessionSlot slot,
            List<GeneratedSetData> sets,
            Instant scheduledStartAt,
            Instant scheduledEndAt,
            AuthPort.AuthActor actor,
            Instant now
    ) {
        ExamSession savedSession = examRepository.saveSession(new ExamSession(
                null,
                exam.id(),
                slot,
                SessionStatus.DRAFT,
                null,
                scheduledStartAt,
                scheduledEndAt,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0
        ));

        List<SetSummaryCard> cards = new ArrayList<>(sets.size());
        for (GeneratedSetData setData : sets) {
            PaperSetStored savedSet = examRepository.savePaperSet(new PaperSetStored(
                    null,
                    savedSession.id(),
                    setData.setNumber(),
                    PaperSetStatus.GENERATED,
                    setData.questionIds().size(),
                    setData.questionIds()
            ));

            for (String label : PaperSetVersionGenerator.VERSION_LABELS) {
                GeneratedVersionData vData = setData.versions().get(label);
                String qJson = jsonCodec.toJson(vData.questions());
                String kJson = jsonCodec.toJson(vData.answerKey());

                EncryptedPayload qEnc = cryptoPort.encrypt(qJson, QBankAad.paperVersionQuestions(savedSet.id(), label));
                EncryptedPayload kEnc = cryptoPort.encrypt(kJson, QBankAad.paperVersionAnswerKey(savedSet.id(), label));
                String vHash = PaperIntegrityCalculator.computeVersionSha256(
                        cryptoPort, label, vData.questions(), vData.answerKey()
                );

                examRepository.savePaperVersion(new PaperVersionStored(
                        null, savedSet.id(), label, qEnc, kEnc, vHash
                ));
            }
            cards.add(new SetSummaryCard(savedSet.setNumber(), savedSet.questionCount(), savedSet.status()));
        }

        auditPort.append(
                "PAPER_GENERATED",
                actor.username(),
                AuthGuard.primaryRoleName(actor),
                "SESSION",
                String.valueOf(savedSession.id()),
                jsonCodec.toJson(Map.of(
                        "examId", exam.id(),
                        "subjectCode", exam.subjectCode(),
                        "slot", slot.name(),
                        "setsGenerated", sets.size(),
                        "questionsPerSet", exam.totalQuestions()
                ))
        );
        eventPublisher.publish(new PaperGeneratedEvent(
                exam.id(),
                savedSession.id(),
                exam.subjectCode(),
                slot,
                actor.username(),
                now
        ));

        return new SessionWithSetCards(savedSession, cards);
    }

    private void verifySelectedModulesReady(Subject subject, List<Integer> modules) {
        Map<Integer, SubjectModule> modMap = loadModuleMap(subject.id());
        for (Integer modNum : modules) {
            if (!isUnitReady(modMap, modNum)) {
                throw new ConflictException(
                        ModuleUnit.displayName(modNum) + " of " + subject.code() + " is not READY (must be uploaded and confirmed by HOD first)");
            }
        }
    }

    private Map<Integer, SubjectModule> loadModuleMap(long subjectId) {
        Map<Integer, SubjectModule> modMap = new LinkedHashMap<>();
        for (SubjectModule m : moduleRepository.findBySubjectId(subjectId)) {
            modMap.put(m.moduleNumber(), m);
        }
        return modMap;
    }

    private static boolean isModReady(SubjectModule m) {
        return m != null && m.status() == ModuleStatus.READY && m.questionCount() > 0;
    }

    private static boolean isUnitReady(Map<Integer, SubjectModule> modMap, int modNum) {
        if (ModuleUnit.isHalf(modNum)) {
            return isModReady(modMap.get(modNum));
        }
        if (isModReady(modMap.get(modNum))) {
            return true;
        }
        return isModReady(modMap.get(ModuleUnit.firstHalf(modNum)))
                && isModReady(modMap.get(ModuleUnit.secondHalf(modNum)));
    }

    private Map<Integer, List<PlainMcq>> loadDecryptedModulePools(long subjectId, List<Integer> modules) {
        Map<Integer, SubjectModule> modMap = loadModuleMap(subjectId);
        Map<Integer, List<PlainMcq>> pool = new LinkedHashMap<>();
        for (Integer modNum : modules) {
            List<Integer> sourceUnits = (ModuleUnit.isFull(modNum) && !isModReady(modMap.get(modNum)))
                    ? List.of(ModuleUnit.firstHalf(modNum), ModuleUnit.secondHalf(modNum))
                    : List.of(modNum);
            List<PlainMcq> decrypted = new ArrayList<>();
            for (Integer srcUnit : sourceUnits) {
                for (EncryptedMcq e : mcqRepository.findBySubjectAndModule(subjectId, srcUnit)) {
                    String text = cryptoPort.decrypt(e.textEnc(), QBankAad.mcqText(subjectId, e.moduleNumber(), e.id()));
                    List<String> opts = jsonCodec.fromJsonStringList(
                            cryptoPort.decrypt(e.optionsEnc(), QBankAad.mcqOptions(subjectId, e.moduleNumber(), e.id())));
                    int correct = Integer.parseInt(
                            cryptoPort.decrypt(e.correctIndexEnc(), QBankAad.mcqCorrectIndex(subjectId, e.moduleNumber(), e.id())));
                    decrypted.add(new PlainMcq(e.id(), subjectId, e.moduleNumber(), e.seq(), text, opts, correct, e.sourceRow()));
                }
            }
            pool.put(modNum, decrypted);
        }
        return pool;
    }

    private PaperSetStored findSetByNumber(long sessionId, int setNumber) {
        return examRepository.findSetsBySessionId(sessionId).stream()
                .filter(s -> s.setNumber() == setNumber)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Set " + setNumber + " not found for session " + sessionId));
    }

    private static void validateWindow(String label, Instant startAt, Instant endAt) {
        if (startAt == null || endAt == null) {
            throw new BadRequestException(label + " session start and end timestamps are required");
        }
        if (!endAt.isAfter(startAt)) {
            throw new BadRequestException(label + " session end time must be strictly after start time");
        }
    }

    public record SessionWithSetCards(
            ExamSession session,
            List<SetSummaryCard> setCards
    ) {
    }

    public record GenerateExamResult(
            Exam exam,
            List<SessionWithSetCards> sessions
    ) {
    }

    public record ApprovalConfirmation(
            Long examId,
            Long sessionId,
            String examTitle,
            String subjectCode,
            String subjectName,
            ExamType examType,
            ExamSessionSlot slot,
            LocalDate examDate,
            int setNumber,
            int questionCount,
            int totalMarks,
            Instant scheduledStartAt,
            Instant scheduledEndAt,
            int version
    ) {
    }
}
