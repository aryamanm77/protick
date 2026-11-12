package com.yourorg.qbank.core.service;

import com.yourorg.qbank.core.exception.BadRequestException;
import com.yourorg.qbank.core.exception.ConflictException;
import com.yourorg.qbank.core.exception.ForbiddenException;
import com.yourorg.qbank.core.exception.NotFoundException;
import com.yourorg.qbank.core.exception.UnauthorizedException;
import com.yourorg.qbank.core.model.ExamSession;
import com.yourorg.qbank.core.model.ExamStudent;
import com.yourorg.qbank.core.model.FrozenVersionView;
import com.yourorg.qbank.core.model.SessionStatus;
import com.yourorg.qbank.core.model.StudentLoginState;
import com.yourorg.qbank.core.model.StudentQuestionView;
import com.yourorg.qbank.core.model.StudentResponseRecord;
import com.yourorg.qbank.core.model.UserRole;
import com.yourorg.qbank.core.model.VersionQuestionSnapshot;
import com.yourorg.qbank.core.port.AuditPort;
import com.yourorg.qbank.core.port.AuthPort;
import com.yourorg.qbank.core.port.ClockPort;
import com.yourorg.qbank.core.port.CryptoPort;
import com.yourorg.qbank.core.port.ExamRepository;
import com.yourorg.qbank.core.port.ExamStudentRepository;
import com.yourorg.qbank.core.port.FrozenPaperProvider;
import com.yourorg.qbank.core.port.JsonCodecPort;
import com.yourorg.qbank.core.port.PasswordHasherPort;
import com.yourorg.qbank.core.port.RandomPort;
import com.yourorg.qbank.core.port.ResponseSink;
import com.yourorg.qbank.core.port.StudentResponseRepository;
import com.yourorg.qbank.core.port.TransactionPort;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Student authentication, single-active-session enforcement, sanitized exam delivery,
 * server-authoritative timing, autosave persistence, and crash/PC-swap resumption.
 * <p>
 * Security: NEVER exposes answer keys, MCQ IDs, module numbers, or marks to the student browser,
 * and strictly blocks paper access outside [scheduledStartAt, studentEffectiveEndAt].
 */
public class CoreStudentExamService {

    private final ExamRepository examRepository;
    private final ExamStudentRepository studentRepository;
    private final StudentResponseRepository responseRepository;
    private final FrozenPaperProvider frozenPaperProvider;
    private final ResponseSink responseSink;
    private final PasswordHasherPort passwordHasher;
    private final CryptoPort cryptoPort;
    private final RandomPort randomPort;
    private final ClockPort clockPort;
    private final TransactionPort transactionPort;
    private final AuditPort auditPort;
    private final JsonCodecPort jsonCodec;
    private final AuthGuard authGuard;

    public CoreStudentExamService(
            ExamRepository examRepository,
            ExamStudentRepository studentRepository,
            StudentResponseRepository responseRepository,
            FrozenPaperProvider frozenPaperProvider,
            ResponseSink responseSink,
            PasswordHasherPort passwordHasher,
            CryptoPort cryptoPort,
            RandomPort randomPort,
            ClockPort clockPort,
            TransactionPort transactionPort,
            AuditPort auditPort,
            JsonCodecPort jsonCodec,
            AuthPort authPort
    ) {
        this.examRepository = Objects.requireNonNull(examRepository);
        this.studentRepository = Objects.requireNonNull(studentRepository);
        this.responseRepository = Objects.requireNonNull(responseRepository);
        this.frozenPaperProvider = Objects.requireNonNull(frozenPaperProvider);
        this.responseSink = Objects.requireNonNull(responseSink);
        this.passwordHasher = Objects.requireNonNull(passwordHasher);
        this.cryptoPort = Objects.requireNonNull(cryptoPort);
        this.randomPort = Objects.requireNonNull(randomPort);
        this.clockPort = Objects.requireNonNull(clockPort);
        this.transactionPort = Objects.requireNonNull(transactionPort);
        this.auditPort = Objects.requireNonNull(auditPort);
        this.jsonCodec = Objects.requireNonNull(jsonCodec);
        this.authGuard = new AuthGuard(authPort);
    }

    public StudentLoginResult authenticateStudent(long sessionId, String rawRollNumber, String rawAccessCode) {
        if (rawRollNumber == null || rawRollNumber.isBlank() || rawAccessCode == null || rawAccessCode.isBlank()) {
            throw new BadRequestException("Roll number and access code are required");
        }
        String roll = rawRollNumber.trim().toUpperCase(Locale.ROOT);
        ExamSession session = requireOpenSession(sessionId);
        ExamStudent student = studentRepository.findBySessionAndRoll(sessionId, roll)
                .orElseThrow(() -> new UnauthorizedException("Invalid roll number or access code"));

        Instant now = clockPort.now();
        Instant effectiveEnd = computeEffectiveEnd(session, student);
        if (now.isBefore(session.scheduledStartAt())) {
            throw new ForbiddenException("Exam session has not started yet (opens at " + session.scheduledStartAt() + ")");
        }
        if (now.isAfter(effectiveEnd)) {
            throw new ForbiddenException("Your exam window ended at " + effectiveEnd);
        }
        if (student.loginState() == StudentLoginState.FINISHED || student.finishedAt() != null) {
            throw new ConflictException("You have already submitted this exam session");
        }
        if (student.loginState() == StudentLoginState.ACTIVE && student.activeTokenJti() != null) {
            throw new ConflictException("Active session already open on another workstation. Ask your invigilator to reset login.");
        }
        if (!passwordHasher.matches(rawAccessCode.trim(), student.accessCodeHash())) {
            auditPort.append(
                    "STUDENT_LOGIN_FAILED",
                    roll,
                    UserRole.STUDENT.name(),
                    "SESSION",
                    String.valueOf(sessionId),
                    "{}"
            );
            throw new UnauthorizedException("Invalid roll number or access code");
        }

        String jti = randomPort.randomUuid();
        ExamStudent loggedIn = studentRepository.save(new ExamStudent(
                student.id(),
                student.sessionId(),
                student.rollNumber(),
                student.fullName(),
                student.roomCode(),
                student.rowNumber(),
                student.seatNumber(),
                student.assignedVersion(),
                student.accessCodeHash(),
                StudentLoginState.ACTIVE,
                jti,
                student.fontScale(),
                student.highContrast(),
                student.accommodationExtraMinutes(),
                student.grantedExtraMinutes(),
                student.lastQuestionIndex(),
                now,
                null,
                student.version()
        ));

        auditPort.append(
                "STUDENT_LOGIN",
                roll,
                UserRole.STUDENT.name(),
                "SESSION",
                String.valueOf(sessionId),
                jsonCodec.toJson(Map.of(
                        "roomCode", loggedIn.roomCode(),
                        "assignedVersion", loggedIn.assignedVersion(),
                        "resumedFromIndex", loggedIn.lastQuestionIndex()
                ))
        );

        return new StudentLoginResult(
                sessionId,
                loggedIn.rollNumber(),
                loggedIn.fullName(),
                loggedIn.assignedVersion(),
                jti,
                session.scheduledStartAt(),
                effectiveEnd,
                loggedIn.fontScale(),
                loggedIn.highContrast(),
                loggedIn.lastQuestionIndex(),
                now
        );
    }

    public StudentPaperPayload getActivePaper(long sessionId, String rollNumber, String tokenJti) {
        authGuard.requireRole(UserRole.STUDENT);
        AuthorizedStudentContext ctx = authorizeActiveStudent(sessionId, rollNumber, tokenJti, false);

        FrozenVersionView versionView = frozenPaperProvider.version(sessionId, ctx.student().assignedVersion());
        List<StudentQuestionView> sanitized = versionView.questions().stream()
                .map(StudentExamSanitizer::toStudentView)
                .toList();

        List<StudentResponseRecord> existingResponses = responseRepository.findBySessionAndRoll(sessionId, ctx.student().rollNumber());
        Map<Integer, Integer> savedSelections = new LinkedHashMap<>();
        for (StudentResponseRecord rec : existingResponses) {
            savedSelections.put(rec.questionPosition(), rec.selectedOptionIndex());
        }

        // Refresh heartbeat on load
        touchHeartbeat(ctx.student(), ctx.student().lastQuestionIndex(), ctx.now());

        return new StudentPaperPayload(
                sessionId,
                ctx.student().rollNumber(),
                ctx.student().fullName(),
                ctx.student().assignedVersion(),
                ctx.session().scheduledStartAt(),
                ctx.effectiveEnd(),
                ctx.now(),
                ctx.student().fontScale(),
                ctx.student().highContrast(),
                ctx.student().lastQuestionIndex(),
                sanitized,
                savedSelections
        );
    }

    public SaveAnswersAck saveAnswers(
            long sessionId,
            String rollNumber,
            String tokenJti,
            List<AnswerSubmissionItem> answers,
            int lastQuestionIndex
    ) {
        authGuard.requireRole(UserRole.STUDENT);
        AuthorizedStudentContext ctx = authorizeActiveStudent(sessionId, rollNumber, tokenJti, true);
        if (answers == null) {
            throw new BadRequestException("Answers payload required");
        }

        return transactionPort.inTransaction(() -> {
            for (AnswerSubmissionItem item : answers) {
                if (item.questionPosition() < 1) {
                    throw new BadRequestException("Invalid question position: " + item.questionPosition());
                }
                if (item.selectedOptionIndex() != null && (item.selectedOptionIndex() < 0 || item.selectedOptionIndex() > 4)) {
                    throw new BadRequestException("Invalid selectedOptionIndex: " + item.selectedOptionIndex());
                }
                responseSink.accept(new StudentResponseRecord(
                        null,
                        sessionId,
                        ctx.student().rollNumber(),
                        item.questionPosition(),
                        item.selectedOptionIndex(),
                        ctx.student().assignedVersion(),
                        item.clientTimestamp(),
                        ctx.now()
                ));
            }
            int safeIndex = Math.max(0, lastQuestionIndex);
            touchHeartbeat(ctx.student(), safeIndex, ctx.now());
            return new SaveAnswersAck(sessionId, answers.size(), ctx.now(), ctx.effectiveEnd());
        });
    }

    public HeartbeatView heartbeat(long sessionId, String rollNumber, String tokenJti, int currentQuestionIndex) {
        authGuard.requireRole(UserRole.STUDENT);
        AuthorizedStudentContext ctx = authorizeActiveStudent(sessionId, rollNumber, tokenJti, false);
        touchHeartbeat(ctx.student(), Math.max(0, currentQuestionIndex), ctx.now());
        return new HeartbeatView(ctx.now(), ctx.effectiveEnd(), ctx.session().status());
    }

    public FinishExamAck finishExam(long sessionId, String rollNumber, String tokenJti, int lastQuestionIndex) {
        authGuard.requireRole(UserRole.STUDENT);
        AuthorizedStudentContext ctx = authorizeActiveStudent(sessionId, rollNumber, tokenJti, true);
        ExamStudent s = ctx.student();
        studentRepository.save(new ExamStudent(
                s.id(), s.sessionId(), s.rollNumber(), s.fullName(), s.roomCode(),
                s.rowNumber(), s.seatNumber(), s.assignedVersion(), s.accessCodeHash(),
                StudentLoginState.FINISHED, null, s.fontScale(), s.highContrast(),
                s.accommodationExtraMinutes(), s.grantedExtraMinutes(),
                Math.max(0, lastQuestionIndex), ctx.now(), ctx.now(), s.version()
        ));
        auditPort.append(
                "STUDENT_FINISHED",
                s.rollNumber(),
                UserRole.STUDENT.name(),
                "SESSION",
                String.valueOf(sessionId),
                "{}"
        );
        return new FinishExamAck(sessionId, s.rollNumber(), ctx.now());
    }

    private AuthorizedStudentContext authorizeActiveStudent(
            long sessionId,
            String rollNumber,
            String tokenJti,
            boolean allowSmallGraceWindow
    ) {
        if (rollNumber == null || rollNumber.isBlank() || tokenJti == null || tokenJti.isBlank()) {
            throw new UnauthorizedException("Invalid student credentials");
        }
        String roll = rollNumber.trim().toUpperCase(Locale.ROOT);
        ExamSession session = requireOpenSession(sessionId);
        ExamStudent student = studentRepository.findBySessionAndRoll(sessionId, roll)
                .orElseThrow(() -> new UnauthorizedException("Student not registered in session"));

        if (student.loginState() == StudentLoginState.FINISHED || student.finishedAt() != null) {
            throw new ConflictException("Exam session already submitted");
        }
        if (student.loginState() != StudentLoginState.ACTIVE || student.activeTokenJti() == null
                || !cryptoPort.constantTimeEquals(student.activeTokenJti(), tokenJti)) {
            throw new UnauthorizedException("Session inactive or reset by invigilator. Please log in again.");
        }

        Instant now = clockPort.now();
        if (now.isBefore(session.scheduledStartAt())) {
            throw new ForbiddenException("Exam paper cannot be accessed before scheduled start time");
        }
        Instant effectiveEnd = computeEffectiveEnd(session, student);
        Instant checkCutoff = allowSmallGraceWindow ? effectiveEnd.plusSeconds(10) : effectiveEnd;
        if (now.isAfter(checkCutoff)) {
            throw new ForbiddenException("Exam window has ended at " + effectiveEnd);
        }
        return new AuthorizedStudentContext(session, student, effectiveEnd, now);
    }

    private void touchHeartbeat(ExamStudent s, int lastQuestionIndex, Instant now) {
        studentRepository.save(new ExamStudent(
                s.id(), s.sessionId(), s.rollNumber(), s.fullName(), s.roomCode(),
                s.rowNumber(), s.seatNumber(), s.assignedVersion(), s.accessCodeHash(),
                s.loginState(), s.activeTokenJti(), s.fontScale(), s.highContrast(),
                s.accommodationExtraMinutes(), s.grantedExtraMinutes(),
                lastQuestionIndex, now, s.finishedAt(), s.version()
        ));
    }

    private ExamSession requireOpenSession(long sessionId) {
        ExamSession session = examRepository.findSessionById(sessionId)
                .orElseThrow(() -> new NotFoundException("Exam session not found: " + sessionId));
        if (session.status() != SessionStatus.FROZEN && session.status() != SessionStatus.LIVE) {
            throw new ForbiddenException("Exam session is not open (status: " + session.status() + ")");
        }
        return session;
    }

    private static Instant computeEffectiveEnd(ExamSession session, ExamStudent student) {
        int extra = student.accommodationExtraMinutes() + student.grantedExtraMinutes();
        return session.scheduledEndAt().plus(extra, ChronoUnit.MINUTES);
    }

    private record AuthorizedStudentContext(
            ExamSession session,
            ExamStudent student,
            Instant effectiveEnd,
            Instant now
    ) {
    }

    private static final class StudentExamSanitizer {
        static StudentQuestionView toStudentView(VersionQuestionSnapshot snap) {
            return new StudentQuestionView(snap.position(), snap.text(), snap.options());
        }
    }

    public record StudentLoginResult(
            long sessionId,
            String rollNumber,
            String fullName,
            String assignedVersion,
            String tokenJti,
            Instant scheduledStartAt,
            Instant effectiveEndAt,
            double fontScale,
            boolean highContrast,
            int lastQuestionIndex,
            Instant serverNow
    ) {
    }

    public record StudentPaperPayload(
            long sessionId,
            String rollNumber,
            String fullName,
            String assignedVersion,
            Instant scheduledStartAt,
            Instant effectiveEndAt,
            Instant serverNow,
            double fontScale,
            boolean highContrast,
            int lastQuestionIndex,
            List<StudentQuestionView> questions,
            Map<Integer, Integer> savedSelections
    ) {
    }

    public record AnswerSubmissionItem(
            int questionPosition,
            Integer selectedOptionIndex,
            Instant clientTimestamp
    ) {
    }

    public record SaveAnswersAck(
            long sessionId,
            int savedCount,
            Instant serverNow,
            Instant effectiveEndAt
    ) {
    }

    public record HeartbeatView(
            Instant serverNow,
            Instant effectiveEndAt,
            SessionStatus sessionStatus
    ) {
    }

    public record FinishExamAck(
            long sessionId,
            String rollNumber,
            Instant finishedAt
    ) {
    }
}
