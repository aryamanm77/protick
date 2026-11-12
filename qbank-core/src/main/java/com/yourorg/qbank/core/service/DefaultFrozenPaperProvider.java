package com.yourorg.qbank.core.service;

import com.yourorg.qbank.core.exception.ForbiddenException;
import com.yourorg.qbank.core.exception.NotFoundException;
import com.yourorg.qbank.core.generator.PaperSetVersionGenerator;
import com.yourorg.qbank.core.model.AnswerKeyEntry;
import com.yourorg.qbank.core.model.Exam;
import com.yourorg.qbank.core.model.ExamSession;
import com.yourorg.qbank.core.model.FrozenPaperMeta;
import com.yourorg.qbank.core.model.FrozenVersionView;
import com.yourorg.qbank.core.model.PaperSetStored;
import com.yourorg.qbank.core.model.PaperVersionStored;
import com.yourorg.qbank.core.model.SessionStatus;
import com.yourorg.qbank.core.model.VersionQuestionSnapshot;
import com.yourorg.qbank.core.port.AuditPort;
import com.yourorg.qbank.core.port.ClockPort;
import com.yourorg.qbank.core.port.CryptoPort;
import com.yourorg.qbank.core.port.ExamRepository;
import com.yourorg.qbank.core.port.ExamStudentRepository;
import com.yourorg.qbank.core.port.FrozenPaperProvider;
import com.yourorg.qbank.core.port.JsonCodecPort;
import com.yourorg.qbank.core.security.PaperIntegrityCalculator;
import com.yourorg.qbank.core.security.QBankAad;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default engine-level {@link FrozenPaperProvider}.
 * <p>
 * Security guarantees:
 * 1) Never decrypts or serves questions/keys before scheduled start time or after effective end time.
 * 2) Recomputes and constant-time-compares SHA-256 hashes over BOTH every version AND the frozen set before returning data.
 * 3) Keeps decrypted questions strictly in an ephemeral in-memory cache purged automatically once window ends.
 */
public class DefaultFrozenPaperProvider implements FrozenPaperProvider {

    private final ExamRepository examRepository;
    private final ExamStudentRepository studentRepository;
    private final CryptoPort cryptoPort;
    private final JsonCodecPort jsonCodec;
    private final ClockPort clockPort;
    private final AuditPort auditPort;
    private final ConcurrentHashMap<Long, CachedFrozenSession> activeCache = new ConcurrentHashMap<>();

    public DefaultFrozenPaperProvider(
            ExamRepository examRepository,
            ExamStudentRepository studentRepository,
            CryptoPort cryptoPort,
            JsonCodecPort jsonCodec,
            ClockPort clockPort,
            AuditPort auditPort
    ) {
        this.examRepository = Objects.requireNonNull(examRepository);
        this.studentRepository = Objects.requireNonNull(studentRepository);
        this.cryptoPort = Objects.requireNonNull(cryptoPort);
        this.jsonCodec = Objects.requireNonNull(jsonCodec);
        this.clockPort = Objects.requireNonNull(clockPort);
        this.auditPort = Objects.requireNonNull(auditPort);
    }

    @Override
    public FrozenPaperMeta get(long sessionId) {
        ExamSession session = requireFrozenOrLiveOrEndedSession(sessionId);
        Exam exam = examRepository.findExamById(session.examId())
                .orElseThrow(() -> new NotFoundException("Exam not found for session " + sessionId));
        return new FrozenPaperMeta(
                session.id(),
                exam.id(),
                exam.subjectCode(),
                exam.subjectName(),
                exam.title(),
                exam.examType(),
                session.sessionSlot(),
                exam.examDate(),
                session.status(),
                session.chosenSetNumber(),
                exam.totalMarks(),
                exam.marksPerQuestion(),
                exam.totalQuestions(),
                session.scheduledStartAt(),
                session.scheduledEndAt(),
                session.actualStartedAt(),
                session.actualEndedAt(),
                session.frozenBy(),
                session.frozenAt(),
                session.contentSha256()
        );
    }

    @Override
    public FrozenVersionView version(long sessionId, String versionLabel) {
        String normalizedLabel = normalizeVersionLabel(versionLabel);
        CachedFrozenSession loaded = loadAndVerifyActiveWindow(sessionId);
        List<VersionQuestionSnapshot> questions = loaded.questionsByVersion().get(normalizedLabel);
        if (questions == null) {
            throw new NotFoundException("Version " + normalizedLabel + " not found for session " + sessionId);
        }
        return new FrozenVersionView(
                sessionId,
                normalizedLabel,
                loaded.session().scheduledStartAt(),
                loaded.session().scheduledEndAt(),
                loaded.exam().totalMarks(),
                loaded.exam().marksPerQuestion(),
                loaded.exam().totalQuestions(),
                questions
        );
    }

    @Override
    public List<AnswerKeyEntry> key(long sessionId, String versionLabel) {
        String normalizedLabel = normalizeVersionLabel(versionLabel);
        CachedFrozenSession loaded = loadAndVerifyActiveWindow(sessionId);
        List<AnswerKeyEntry> keys = loaded.keysByVersion().get(normalizedLabel);
        if (keys == null) {
            throw new NotFoundException("Version key " + normalizedLabel + " not found for session " + sessionId);
        }
        return keys;
    }

    /**
     * Internal scoring helper allowing authorized server-side scoring after an exam ends
     * without loosening the live-window contract of {@link #key(long, String)}.
     */
    public List<AnswerKeyEntry> decryptVerifiedKeyForScoring(long sessionId, String versionLabel) {
        String normalizedLabel = normalizeVersionLabel(versionLabel);
        ExamSession session = requireFrozenOrLiveOrEndedSession(sessionId);
        if (session.status() != SessionStatus.ENDED && session.status() != SessionStatus.LIVE) {
            throw new ForbiddenException("Scoring requires session to be LIVE or ENDED");
        }
        CachedFrozenSession verified = verifyAndDecryptSession(session);
        List<AnswerKeyEntry> keys = verified.keysByVersion().get(normalizedLabel);
        if (keys == null) {
            throw new NotFoundException("Version key " + normalizedLabel + " not found for session " + sessionId);
        }
        return keys;
    }

    public void evictSession(long sessionId) {
        activeCache.remove(sessionId);
    }

    private CachedFrozenSession loadAndVerifyActiveWindow(long sessionId) {
        ExamSession session = examRepository.findSessionById(sessionId)
                .orElseThrow(() -> new NotFoundException("Exam session not found: " + sessionId));

        if (session.status() != SessionStatus.FROZEN && session.status() != SessionStatus.LIVE) {
            activeCache.remove(sessionId);
            throw new ForbiddenException("Exam paper is not open (status: " + session.status() + ")");
        }
        if (session.chosenSetNumber() == null || session.contentSha256() == null) {
            throw new ForbiddenException("Exam session has no frozen paper hash");
        }

        Instant now = clockPort.now();
        if (now.isBefore(session.scheduledStartAt())) {
            activeCache.remove(sessionId);
            throw new ForbiddenException("Exam paper cannot be accessed before scheduled start time: " + session.scheduledStartAt());
        }
        int maxExtraMinutes = studentRepository.maxExtraTimeForSession(sessionId);
        Instant effectiveCutoff = session.scheduledEndAt().plus(maxExtraMinutes, ChronoUnit.MINUTES);
        if (now.isAfter(effectiveCutoff)) {
            activeCache.remove(sessionId);
            throw new ForbiddenException("Exam paper window has expired at " + effectiveCutoff);
        }

        CachedFrozenSession cached = activeCache.get(sessionId);
        if (cached != null && cryptoPort.constantTimeEquals(cached.session().contentSha256(), session.contentSha256())) {
            return cached;
        }
        CachedFrozenSession freshlyVerified = verifyAndDecryptSession(session);
        activeCache.put(sessionId, freshlyVerified);
        return freshlyVerified;
    }

    private CachedFrozenSession verifyAndDecryptSession(ExamSession session) {
        Exam exam = examRepository.findExamById(session.examId())
                .orElseThrow(() -> new NotFoundException("Exam not found: " + session.examId()));

        List<PaperSetStored> sets = examRepository.findSetsBySessionId(session.id());
        PaperSetStored chosenSet = sets.stream()
                .filter(s -> Objects.equals(s.setNumber(), session.chosenSetNumber()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Chosen paper set not found for session " + session.id()));

        List<PaperVersionStored> storedVersions = examRepository.findVersionsBySetId(chosenSet.id());
        if (storedVersions.size() != PaperSetVersionGenerator.VERSION_LABELS.size()) {
            recordTamperAlert(session.id(), "Missing paper version rows for frozen set " + chosenSet.id());
            throw new ForbiddenException("Frozen paper integrity verification failed");
        }

        String recomputedSetHash = PaperIntegrityCalculator.computeSessionSetSha256(
                cryptoPort, chosenSet.setNumber(), storedVersions);
        if (!cryptoPort.constantTimeEquals(recomputedSetHash, session.contentSha256())) {
            recordTamperAlert(session.id(), "Frozen session hash mismatch");
            throw new ForbiddenException("Frozen paper integrity verification failed");
        }

        Map<String, List<VersionQuestionSnapshot>> qMap = new LinkedHashMap<>();
        Map<String, List<AnswerKeyEntry>> kMap = new LinkedHashMap<>();

        for (PaperVersionStored pv : storedVersions) {
            String qJson = cryptoPort.decrypt(pv.questionsEnc(), QBankAad.paperVersionQuestions(chosenSet.id(), pv.versionLabel()));
            String kJson = cryptoPort.decrypt(pv.answerKeyEnc(), QBankAad.paperVersionAnswerKey(chosenSet.id(), pv.versionLabel()));

            List<VersionQuestionSnapshot> questions = List.copyOf(jsonCodec.fromJsonList(qJson, VersionQuestionSnapshot.class));
            List<AnswerKeyEntry> keys = List.copyOf(jsonCodec.fromJsonList(kJson, AnswerKeyEntry.class));

            String recomputedVersionHash = PaperIntegrityCalculator.computeVersionSha256(
                    cryptoPort, pv.versionLabel(), questions, keys);
            if (!cryptoPort.constantTimeEquals(recomputedVersionHash, pv.contentSha256())) {
                recordTamperAlert(session.id(), "Tamper detected in version " + pv.versionLabel());
                throw new ForbiddenException("Paper version " + pv.versionLabel() + " integrity verification failed");
            }
            qMap.put(pv.versionLabel(), questions);
            kMap.put(pv.versionLabel(), keys);
        }

        return new CachedFrozenSession(exam, session, Map.copyOf(qMap), Map.copyOf(kMap));
    }

    private ExamSession requireFrozenOrLiveOrEndedSession(long sessionId) {
        ExamSession session = examRepository.findSessionById(sessionId)
                .orElseThrow(() -> new NotFoundException("Exam session not found: " + sessionId));
        if (session.chosenSetNumber() == null || session.status() == SessionStatus.DRAFT) {
            throw new ForbiddenException("Exam session is not frozen yet");
        }
        return session;
    }

    private void recordTamperAlert(long sessionId, String reason) {
        auditPort.append(
                "PAPER_INTEGRITY_TAMPER_ALERT",
                "SYSTEM",
                "ENGINE",
                "SESSION",
                String.valueOf(sessionId),
                jsonCodec.toJson(Map.of("reason", reason))
        );
    }

    private static String normalizeVersionLabel(String label) {
        if (label == null || label.isBlank()) {
            throw new NotFoundException("Version label required (A-D)");
        }
        return label.trim().toUpperCase(Locale.ROOT);
    }

    private record CachedFrozenSession(
            Exam exam,
            ExamSession session,
            Map<String, List<VersionQuestionSnapshot>> questionsByVersion,
            Map<String, List<AnswerKeyEntry>> keysByVersion
    ) {
    }
}
