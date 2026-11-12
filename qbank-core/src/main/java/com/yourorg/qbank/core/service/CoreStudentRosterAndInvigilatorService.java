package com.yourorg.qbank.core.service;

import com.yourorg.qbank.core.exception.BadRequestException;
import com.yourorg.qbank.core.exception.ConflictException;
import com.yourorg.qbank.core.exception.ForbiddenException;
import com.yourorg.qbank.core.exception.NotFoundException;
import com.yourorg.qbank.core.generator.CheckerboardSeatVersionPolicy;
import com.yourorg.qbank.core.generator.PaperSetVersionGenerator;
import com.yourorg.qbank.core.generator.VersionAssignmentPolicy;
import com.yourorg.qbank.core.model.ExamSession;
import com.yourorg.qbank.core.model.ExamStudent;
import com.yourorg.qbank.core.model.ExtraTimeGrant;
import com.yourorg.qbank.core.model.SessionStatus;
import com.yourorg.qbank.core.model.StudentLoginState;
import com.yourorg.qbank.core.model.StudentResponseRecord;
import com.yourorg.qbank.core.model.UserRole;
import com.yourorg.qbank.core.port.AuditPort;
import com.yourorg.qbank.core.port.AuthPort;
import com.yourorg.qbank.core.port.ClockPort;
import com.yourorg.qbank.core.port.ExamRepository;
import com.yourorg.qbank.core.port.ExamStudentRepository;
import com.yourorg.qbank.core.port.ExtraTimeGrantRepository;
import com.yourorg.qbank.core.port.JsonCodecPort;
import com.yourorg.qbank.core.port.PasswordHasherPort;
import com.yourorg.qbank.core.port.RandomPort;
import com.yourorg.qbank.core.port.StudentResponseRepository;
import com.yourorg.qbank.core.port.TransactionPort;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Handles COE student roster management (with server-side Version A-D assignment and one-time passcodes)
 * and INVIGILATOR room monitoring, login reset (on PC crash/second login), and extra-time grants.
 */
public class CoreStudentRosterAndInvigilatorService {

    private static final long HEARTBEAT_STALE_SECONDS = 45L;

    private final ExamRepository examRepository;
    private final ExamStudentRepository studentRepository;
    private final StudentResponseRepository responseRepository;
    private final ExtraTimeGrantRepository extraTimeGrantRepository;
    private final PasswordHasherPort passwordHasher;
    private final RandomPort randomPort;
    private final ClockPort clockPort;
    private final TransactionPort transactionPort;
    private final AuditPort auditPort;
    private final JsonCodecPort jsonCodec;
    private final AuthGuard authGuard;
    private final VersionAssignmentPolicy versionPolicy = new CheckerboardSeatVersionPolicy();

    public CoreStudentRosterAndInvigilatorService(
            ExamRepository examRepository,
            ExamStudentRepository studentRepository,
            StudentResponseRepository responseRepository,
            ExtraTimeGrantRepository extraTimeGrantRepository,
            PasswordHasherPort passwordHasher,
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
        this.extraTimeGrantRepository = Objects.requireNonNull(extraTimeGrantRepository);
        this.passwordHasher = Objects.requireNonNull(passwordHasher);
        this.randomPort = Objects.requireNonNull(randomPort);
        this.clockPort = Objects.requireNonNull(clockPort);
        this.transactionPort = Objects.requireNonNull(transactionPort);
        this.auditPort = Objects.requireNonNull(auditPort);
        this.jsonCodec = Objects.requireNonNull(jsonCodec);
        this.authGuard = new AuthGuard(authPort);
    }

    public List<IssuedStudentCredential> registerStudents(long sessionId, List<StudentRegistrationRequest> requests) {
        AuthPort.AuthActor actor = authGuard.requireRole(UserRole.COE);
        if (requests == null || requests.isEmpty()) {
            throw new BadRequestException("At least one student registration entry is required");
        }
        ExamSession session = examRepository.findSessionById(sessionId)
                .orElseThrow(() -> new NotFoundException("Exam session not found: " + sessionId));
        if (session.status() == SessionStatus.ENDED) {
            throw new ConflictException("Cannot register students for an ENDED session");
        }

        return transactionPort.inTransaction(() -> {
            List<IssuedStudentCredential> issued = new ArrayList<>(requests.size());
            for (StudentRegistrationRequest req : requests) {
                String roll = normalizeRoll(req.rollNumber());
                String name = normalizeFullName(req.fullName());
                String room = normalizeRoomCode(req.roomCode());
                if (studentRepository.findBySessionAndRoll(sessionId, roll).isPresent()) {
                    throw new ConflictException("Roll number already registered in session: " + roll);
                }
                double fontScale = validateFontScale(req.fontScale());
                int accomMinutes = validateExtraMinutes(req.accommodationExtraMinutes(), 0, 180);

                String assignedVersion = versionPolicy.assignVersion(
                        roll, req.rowNumber(), req.seatNumber(), PaperSetVersionGenerator.VERSION_LABELS
                );
                String plainAccessCode = randomPort.generateAccessCode();
                String codeHash = passwordHasher.hash(plainAccessCode);

                ExamStudent saved = studentRepository.save(new ExamStudent(
                        null,
                        sessionId,
                        roll,
                        name,
                        room,
                        req.rowNumber(),
                        req.seatNumber(),
                        assignedVersion,
                        codeHash,
                        StudentLoginState.NOT_STARTED,
                        null,
                        fontScale,
                        req.highContrast(),
                        accomMinutes,
                        0,
                        0,
                        null,
                        null,
                        0
                ));

                auditPort.append(
                        "STUDENT_VERSION_ASSIGNED",
                        actor.username(),
                        AuthGuard.primaryRoleName(actor),
                        "EXAM_STUDENT",
                        sessionId + ":" + roll,
                        jsonCodec.toJson(Map.of(
                                "roomCode", room,
                                "assignedVersion", assignedVersion,
                                "accommodationExtraMinutes", accomMinutes
                        ))
                );
                issued.add(new IssuedStudentCredential(
                        saved.rollNumber(),
                        saved.fullName(),
                        saved.roomCode(),
                        saved.rowNumber(),
                        saved.seatNumber(),
                        saved.assignedVersion(),
                        plainAccessCode,
                        saved.fontScale(),
                        saved.highContrast(),
                        saved.accommodationExtraMinutes()
                ));
            }
            return issued;
        });
    }

    public List<RosterStudentSummary> listRosterForCoe(long sessionId) {
        authGuard.requireRole(UserRole.COE);
        examRepository.findSessionById(sessionId)
                .orElseThrow(() -> new NotFoundException("Exam session not found: " + sessionId));
        return studentRepository.findBySessionId(sessionId).stream()
                .map(s -> new RosterStudentSummary(
                        s.rollNumber(),
                        s.fullName(),
                        s.roomCode(),
                        s.rowNumber(),
                        s.seatNumber(),
                        s.assignedVersion(),
                        s.loginState(),
                        s.fontScale(),
                        s.highContrast(),
                        s.accommodationExtraMinutes(),
                        s.grantedExtraMinutes()
                ))
                .toList();
    }

    public RosterStudentSummary updateAccommodation(
            long sessionId,
            String rollNumber,
            double fontScale,
            boolean highContrast,
            int accommodationExtraMinutes
    ) {
        AuthPort.AuthActor actor = authGuard.requireRole(UserRole.COE);
        String roll = normalizeRoll(rollNumber);
        ExamStudent student = studentRepository.findBySessionAndRoll(sessionId, roll)
                .orElseThrow(() -> new NotFoundException("Student not found in session: " + roll));
        double validScale = validateFontScale(fontScale);
        int validExtra = validateExtraMinutes(accommodationExtraMinutes, 0, 180);

        ExamStudent updated = studentRepository.save(new ExamStudent(
                student.id(),
                student.sessionId(),
                student.rollNumber(),
                student.fullName(),
                student.roomCode(),
                student.rowNumber(),
                student.seatNumber(),
                student.assignedVersion(),
                student.accessCodeHash(),
                student.loginState(),
                student.activeTokenJti(),
                validScale,
                highContrast,
                validExtra,
                student.grantedExtraMinutes(),
                student.lastQuestionIndex(),
                student.lastHeartbeatAt(),
                student.finishedAt(),
                student.version()
        ));

        auditPort.append(
                "STUDENT_ACCOMMODATION_UPDATED",
                actor.username(),
                AuthGuard.primaryRoleName(actor),
                "EXAM_STUDENT",
                sessionId + ":" + roll,
                jsonCodec.toJson(Map.of(
                        "fontScale", validScale,
                        "highContrast", highContrast,
                        "accommodationExtraMinutes", validExtra
                ))
        );
        return toSummary(updated);
    }

    public IssuedStudentCredential reissueAccessCode(long sessionId, String rollNumber) {
        AuthPort.AuthActor actor = authGuard.requireRole(UserRole.COE);
        String roll = normalizeRoll(rollNumber);
        ExamStudent student = studentRepository.findBySessionAndRoll(sessionId, roll)
                .orElseThrow(() -> new NotFoundException("Student not found in session: " + roll));
        if (student.loginState() == StudentLoginState.FINISHED) {
            throw new ConflictException("Cannot reissue code for an already finished student");
        }
        String plainCode = randomPort.generateAccessCode();
        ExamStudent updated = studentRepository.save(new ExamStudent(
                student.id(),
                student.sessionId(),
                student.rollNumber(),
                student.fullName(),
                student.roomCode(),
                student.rowNumber(),
                student.seatNumber(),
                student.assignedVersion(),
                passwordHasher.hash(plainCode),
                student.loginState() == StudentLoginState.ACTIVE ? StudentLoginState.DISCONNECTED : student.loginState(),
                null,
                student.fontScale(),
                student.highContrast(),
                student.accommodationExtraMinutes(),
                student.grantedExtraMinutes(),
                student.lastQuestionIndex(),
                student.lastHeartbeatAt(),
                student.finishedAt(),
                student.version()
        ));
        auditPort.append(
                "STUDENT_PASSCODE_REISSUED",
                actor.username(),
                AuthGuard.primaryRoleName(actor),
                "EXAM_STUDENT",
                sessionId + ":" + roll,
                "{}"
        );
        return new IssuedStudentCredential(
                updated.rollNumber(), updated.fullName(), updated.roomCode(),
                updated.rowNumber(), updated.seatNumber(), updated.assignedVersion(),
                plainCode, updated.fontScale(), updated.highContrast(), updated.accommodationExtraMinutes()
        );
    }

    public RoomMonitorView getRoomMonitor(long sessionId, String requestedRoomCode) {
        AuthPort.AuthActor actor = authGuard.requireRole(UserRole.INVIGILATOR);
        ExamSession session = examRepository.findSessionById(sessionId)
                .orElseThrow(() -> new NotFoundException("Exam session not found: " + sessionId));

        String effectiveRoom = resolveAuthorizedRoom(actor, requestedRoomCode);
        List<ExamStudent> students = effectiveRoom == null
                ? studentRepository.findBySessionId(sessionId)
                : studentRepository.findBySessionAndRoom(sessionId, effectiveRoom);

        Map<String, Long> answeredCountByRoll = responseRepository.findBySessionId(sessionId).stream()
                .filter(r -> r.selectedOptionIndex() != null)
                .collect(Collectors.groupingBy(StudentResponseRecord::rollNumber, Collectors.counting()));

        Instant now = clockPort.now();
        int activeCount = 0;
        int disconnectedCount = 0;
        int finishedCount = 0;
        int notStartedCount = 0;

        List<InvigilatorStudentStatus> rows = new ArrayList<>(students.size());
        for (ExamStudent s : students) {
            StudentLoginState liveState = computeObservedState(s, now);
            switch (liveState) {
                case ACTIVE -> activeCount++;
                case DISCONNECTED -> disconnectedCount++;
                case FINISHED -> finishedCount++;
                case NOT_STARTED -> notStartedCount++;
            }
            int totalExtra = s.accommodationExtraMinutes() + s.grantedExtraMinutes();
            Instant effEnd = session.scheduledEndAt() == null
                    ? null
                    : session.scheduledEndAt().plus(totalExtra, ChronoUnit.MINUTES);
            rows.add(new InvigilatorStudentStatus(
                    s.rollNumber(),
                    s.fullName(),
                    s.roomCode(),
                    s.rowNumber(),
                    s.seatNumber(),
                    liveState,
                    answeredCountByRoll.getOrDefault(s.rollNumber(), 0L).intValue(),
                    s.lastQuestionIndex(),
                    totalExtra,
                    effEnd,
                    s.lastHeartbeatAt(),
                    s.finishedAt()
            ));
        }

        List<ExtraTimeGrant> recentGrants = extraTimeGrantRepository.findBySessionId(sessionId).stream()
                .filter(g -> effectiveRoom == null || effectiveRoom.equalsIgnoreCase(g.roomCode()) || g.roomCode() == null)
                .toList();

        return new RoomMonitorView(
                sessionId,
                effectiveRoom == null ? "ALL_ROOMS" : effectiveRoom,
                session.status(),
                session.scheduledStartAt(),
                session.scheduledEndAt(),
                now,
                students.size(),
                activeCount,
                disconnectedCount,
                finishedCount,
                notStartedCount,
                rows,
                recentGrants
        );
    }

    public void resetStudentLogin(long sessionId, String rollNumber, String reason) {
        AuthPort.AuthActor actor = authGuard.requireRole(UserRole.INVIGILATOR);
        String roll = normalizeRoll(rollNumber);
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("Reason is required when resetting a student login");
        }
        ExamStudent student = studentRepository.findBySessionAndRoll(sessionId, roll)
                .orElseThrow(() -> new NotFoundException("Student not found in session: " + roll));
        enforceInvigilatorRoomAccess(actor, student.roomCode());

        if (student.loginState() == StudentLoginState.FINISHED || student.finishedAt() != null) {
            throw new ConflictException("Cannot reset login for a finished student");
        }

        StudentLoginState nextState = student.loginState() == StudentLoginState.NOT_STARTED
                ? StudentLoginState.NOT_STARTED
                : StudentLoginState.DISCONNECTED;

        studentRepository.save(new ExamStudent(
                student.id(),
                student.sessionId(),
                student.rollNumber(),
                student.fullName(),
                student.roomCode(),
                student.rowNumber(),
                student.seatNumber(),
                student.assignedVersion(),
                student.accessCodeHash(),
                nextState,
                null,
                student.fontScale(),
                student.highContrast(),
                student.accommodationExtraMinutes(),
                student.grantedExtraMinutes(),
                student.lastQuestionIndex(),
                student.lastHeartbeatAt(),
                student.finishedAt(),
                student.version()
        ));

        auditPort.append(
                "INVIGILATOR_RESET_STUDENT_LOGIN",
                actor.username(),
                AuthGuard.primaryRoleName(actor),
                "EXAM_STUDENT",
                sessionId + ":" + roll,
                jsonCodec.toJson(Map.of(
                        "roomCode", student.roomCode(),
                        "reason", reason.trim()
                ))
        );
    }

    public ExtraTimeGrant grantExtraTime(
            long sessionId,
            String roomCode,
            String rollNumberOrNull,
            int extraMinutes,
            String reason
    ) {
        AuthPort.AuthActor actor = authGuard.requireRole(UserRole.INVIGILATOR);
        int validExtra = validateExtraMinutes(extraMinutes, 1, 120);
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("Reason is required for extra time grants");
        }
        ExamSession session = examRepository.findSessionById(sessionId)
                .orElseThrow(() -> new NotFoundException("Exam session not found: " + sessionId));
        if (session.status() != SessionStatus.FROZEN && session.status() != SessionStatus.LIVE) {
            throw new ConflictException("Extra time can only be granted for FROZEN or LIVE sessions");
        }

        boolean singleStudent = rollNumberOrNull != null && !rollNumberOrNull.isBlank();
        String targetRoll = singleStudent ? normalizeRoll(rollNumberOrNull) : null;
        String authorizedRoom = singleStudent ? null : resolveAuthorizedRoom(actor, roomCode);
        if (!singleStudent && (authorizedRoom == null || authorizedRoom.isBlank())) {
            throw new BadRequestException("Room code is required for whole-room extra time grant");
        }

        Instant now = clockPort.now();
        return transactionPort.inTransaction(() -> {
            String effectiveRoom;
            if (singleStudent) {
                ExamStudent s = studentRepository.findBySessionAndRoll(sessionId, targetRoll)
                        .orElseThrow(() -> new NotFoundException("Student not found in session: " + targetRoll));
                enforceInvigilatorRoomAccess(actor, s.roomCode());
                effectiveRoom = s.roomCode();
                studentRepository.save(withAdditionalExtraMinutes(s, validExtra));
            } else {
                List<ExamStudent> roomStudents = studentRepository.findBySessionAndRoom(sessionId, authorizedRoom);
                if (roomStudents.isEmpty()) {
                    throw new NotFoundException("No students found in room " + authorizedRoom + " for session " + sessionId);
                }
                effectiveRoom = authorizedRoom;
                for (ExamStudent s : roomStudents) {
                    studentRepository.save(withAdditionalExtraMinutes(s, validExtra));
                }
            }

            ExtraTimeGrant savedGrant = extraTimeGrantRepository.save(new ExtraTimeGrant(
                    null,
                    sessionId,
                    effectiveRoom,
                    targetRoll,
                    validExtra,
                    reason.trim(),
                    actor.username(),
                    now
            ));

            auditPort.append(
                    "EXTRA_TIME_GRANTED",
                    actor.username(),
                    AuthGuard.primaryRoleName(actor),
                    singleStudent ? "EXAM_STUDENT" : "ROOM",
                    singleStudent ? (sessionId + ":" + targetRoll) : (sessionId + ":" + effectiveRoom),
                    jsonCodec.toJson(Map.of(
                            "extraMinutes", validExtra,
                            "roomCode", effectiveRoom,
                            "rollNumber", targetRoll == null ? "" : targetRoll,
                            "reason", reason.trim()
                    ))
            );
            return savedGrant;
        });
    }

    private static ExamStudent withAdditionalExtraMinutes(ExamStudent s, int addMinutes) {
        return new ExamStudent(
                s.id(), s.sessionId(), s.rollNumber(), s.fullName(), s.roomCode(),
                s.rowNumber(), s.seatNumber(), s.assignedVersion(), s.accessCodeHash(),
                s.loginState(), s.activeTokenJti(), s.fontScale(), s.highContrast(),
                s.accommodationExtraMinutes(), s.grantedExtraMinutes() + addMinutes,
                s.lastQuestionIndex(), s.lastHeartbeatAt(), s.finishedAt(), s.version()
        );
    }

    private static StudentLoginState computeObservedState(ExamStudent s, Instant now) {
        if (s.loginState() == StudentLoginState.ACTIVE && s.lastHeartbeatAt() != null
                && s.lastHeartbeatAt().isBefore(now.minusSeconds(HEARTBEAT_STALE_SECONDS))) {
            return StudentLoginState.DISCONNECTED;
        }
        return s.loginState();
    }

    private static String resolveAuthorizedRoom(AuthPort.AuthActor actor, String requestedRoomCode) {
        String assigned = actor.assignedRoomCode() == null || actor.assignedRoomCode().isBlank()
                ? null : actor.assignedRoomCode().trim().toUpperCase(Locale.ROOT);
        String requested = requestedRoomCode == null || requestedRoomCode.isBlank()
                ? null : requestedRoomCode.trim().toUpperCase(Locale.ROOT);

        if (assigned != null) {
            if (requested != null && !assigned.equals(requested)) {
                throw new ForbiddenException("Invigilator restricted to room " + assigned);
            }
            return assigned;
        }
        return requested;
    }

    private static void enforceInvigilatorRoomAccess(AuthPort.AuthActor actor, String targetRoom) {
        if (actor.assignedRoomCode() != null && !actor.assignedRoomCode().isBlank()) {
            String assigned = actor.assignedRoomCode().trim().toUpperCase(Locale.ROOT);
            if (!assigned.equalsIgnoreCase(targetRoom)) {
                throw new ForbiddenException("Invigilator restricted to room " + assigned);
            }
        }
    }

    private static RosterStudentSummary toSummary(ExamStudent s) {
        return new RosterStudentSummary(
                s.rollNumber(), s.fullName(), s.roomCode(), s.rowNumber(), s.seatNumber(),
                s.assignedVersion(), s.loginState(), s.fontScale(), s.highContrast(),
                s.accommodationExtraMinutes(), s.grantedExtraMinutes()
        );
    }

    private static String normalizeRoll(String roll) {
        if (roll == null || roll.isBlank()) {
            throw new BadRequestException("Roll number is required");
        }
        return roll.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeFullName(String name) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("Full name is required");
        }
        return name.trim();
    }

    private static String normalizeRoomCode(String room) {
        if (room == null || room.isBlank()) {
            return "MAIN";
        }
        return room.trim().toUpperCase(Locale.ROOT);
    }

    private static double validateFontScale(double fontScale) {
        double v = fontScale <= 0 ? 1.0 : fontScale;
        if (v < 0.8 || v > 2.5) {
            throw new BadRequestException("Font scale must be between 0.8 and 2.5");
        }
        return v;
    }

    private static int validateExtraMinutes(int minutes, int min, int max) {
        if (minutes < min || minutes > max) {
            throw new BadRequestException("Extra minutes must be between " + min + " and " + max);
        }
        return minutes;
    }

    public record StudentRegistrationRequest(
            String rollNumber,
            String fullName,
            String roomCode,
            Integer rowNumber,
            Integer seatNumber,
            double fontScale,
            boolean highContrast,
            int accommodationExtraMinutes
    ) {
    }

    public record IssuedStudentCredential(
            String rollNumber,
            String fullName,
            String roomCode,
            Integer rowNumber,
            Integer seatNumber,
            String assignedVersion,
            String oneTimeAccessCode,
            double fontScale,
            boolean highContrast,
            int accommodationExtraMinutes
    ) {
    }

    public record RosterStudentSummary(
            String rollNumber,
            String fullName,
            String roomCode,
            Integer rowNumber,
            Integer seatNumber,
            String assignedVersion,
            StudentLoginState loginState,
            double fontScale,
            boolean highContrast,
            int accommodationExtraMinutes,
            int grantedExtraMinutes
    ) {
    }

    public record InvigilatorStudentStatus(
            String rollNumber,
            String fullName,
            String roomCode,
            Integer rowNumber,
            Integer seatNumber,
            StudentLoginState observedState,
            int answeredCount,
            int lastQuestionIndex,
            int totalExtraMinutes,
            Instant effectiveEndAt,
            Instant lastHeartbeatAt,
            Instant finishedAt
    ) {
    }

    public record RoomMonitorView(
            long sessionId,
            String roomCode,
            SessionStatus sessionStatus,
            Instant scheduledStartAt,
            Instant scheduledEndAt,
            Instant serverNow,
            int totalStudents,
            int activeCount,
            int disconnectedCount,
            int finishedCount,
            int notStartedCount,
            List<InvigilatorStudentStatus> students,
            List<ExtraTimeGrant> recentGrants
    ) {
    }
}
