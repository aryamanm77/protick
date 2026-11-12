package com.yourorg.qbank.starter.web;

import com.yourorg.qbank.core.exception.RateLimitException;
import com.yourorg.qbank.core.exception.UnauthorizedException;
import com.yourorg.qbank.core.model.*;
import com.yourorg.qbank.core.port.AuthPort;
import com.yourorg.qbank.core.port.ClockPort;
import com.yourorg.qbank.core.port.ExamRepository;
import com.yourorg.qbank.core.service.CoreStudentExamService;
import com.yourorg.qbank.starter.config.QBankProperties;
import com.yourorg.qbank.starter.security.IpRateLimiter;
import com.yourorg.qbank.starter.security.StudentTokenCodec;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("${qbank.base-path:/qbank}/api/v1/student")
public class QBankStudentController {

    private final CoreStudentExamService studentService;
    private final StudentTokenCodec tokenCodec;
    private final ExamRepository examRepository;
    private final AuthPort authPort;
    private final ClockPort clockPort;
    private final QBankProperties props;
    private final IpRateLimiter loginRateLimiter;

    public QBankStudentController(
            CoreStudentExamService studentService,
            StudentTokenCodec tokenCodec,
            ExamRepository examRepository,
            AuthPort authPort,
            ClockPort clockPort,
            QBankProperties props
    ) {
        this.studentService = studentService;
        this.tokenCodec = tokenCodec;
        this.examRepository = examRepository;
        this.authPort = authPort;
        this.clockPort = clockPort;
        this.props = props;
        this.loginRateLimiter = new IpRateLimiter(props.getSecurity().getLoginRateLimitPerMinute(), 60L);
    }

    @GetMapping("/public-sessions")
    public List<PublicSessionOption> listOpenSessions() {
        Instant now = clockPort.now();
        Map<Long, Exam> exams = examRepository.findAllExams().stream()
                .collect(Collectors.toMap(Exam::id, e -> e));
        return examRepository.findAllSessions().stream()
                .filter(s -> s.status() == SessionStatus.FROZEN || s.status() == SessionStatus.LIVE)
                .filter(s -> !now.isBefore(s.scheduledStartAt()) && !now.isAfter(s.scheduledEndAt().plus(4, ChronoUnit.HOURS)))
                .map(s -> {
                    Exam e = exams.get(s.examId());
                    return new PublicSessionOption(
                            s.id(),
                            e != null ? e.subjectCode() : "",
                            e != null ? e.title() : "",
                            s.sessionSlot(),
                            s.scheduledStartAt(),
                            s.scheduledEndAt()
                    );
                })
                .toList();
    }

    @PostMapping("/login")
    public StudentLoginHttpResponse login(@RequestBody StudentLoginRequest req, HttpServletRequest http) {
        String ip = http.getRemoteAddr() == null ? "unknown" : http.getRemoteAddr();
        if (!loginRateLimiter.tryConsume("stu:" + ip, clockPort.now())) {
            throw new RateLimitException("Too many student login attempts from this terminal. Please wait 60 seconds.");
        }
        var res = studentService.authenticateStudent(req.sessionId(), req.rollNumber(), req.accessCode());
        Instant tokenExp = res.effectiveEndAt().plus(props.getSecurity().getStudentTokenTtlMinutes(), ChronoUnit.MINUTES);
        String jwt = tokenCodec.issueToken(new StudentTokenCodec.StudentClaims(
                res.sessionId(), res.rollNumber(), res.assignedVersion(), res.tokenJti(), tokenExp
        ));
        return new StudentLoginHttpResponse(
                jwt,
                res.sessionId(),
                res.rollNumber(),
                res.fullName(),
                res.assignedVersion(),
                res.scheduledStartAt(),
                res.effectiveEndAt(),
                res.fontScale(),
                res.highContrast(),
                res.lastQuestionIndex(),
                res.serverNow()
        );
    }

    @GetMapping("/paper")
    public CoreStudentExamService.StudentPaperPayload getPaper(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        var claims = requireStudentClaims(authHeader);
        return studentService.getActivePaper(claims.sessionId(), claims.rollNumber(), claims.jti());
    }

    @PostMapping("/responses")
    public CoreStudentExamService.SaveAnswersAck saveResponses(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody SaveResponsesRequest req
    ) {
        var claims = requireStudentClaims(authHeader);
        return studentService.saveAnswers(
                claims.sessionId(), claims.rollNumber(), claims.jti(), req.answers(), req.lastQuestionIndex()
        );
    }

    @PostMapping("/heartbeat")
    public CoreStudentExamService.HeartbeatView heartbeat(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody HeartbeatRequest req
    ) {
        var claims = requireStudentClaims(authHeader);
        return studentService.heartbeat(
                claims.sessionId(), claims.rollNumber(), claims.jti(), req.currentQuestionIndex()
        );
    }

    @PostMapping("/finish")
    public CoreStudentExamService.FinishExamAck finish(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody FinishRequest req
    ) {
        var claims = requireStudentClaims(authHeader);
        return studentService.finishExam(
                claims.sessionId(), claims.rollNumber(), claims.jti(), req.lastQuestionIndex()
        );
    }

    private StudentTokenCodec.StudentClaims requireStudentClaims(String authHeader) {
        var actor = authPort.currentActor()
                .filter(a -> a.roles().contains(UserRole.STUDENT) && a.studentSessionId() != null)
                .orElseThrow(() -> new UnauthorizedException("Valid student session token required"));
        if (authHeader == null || !authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new UnauthorizedException("Bearer student token required");
        }
        String raw = authHeader.substring(7).trim();
        var claims = tokenCodec.parseAndVerify(raw)
                .orElseThrow(() -> new UnauthorizedException("Expired or invalid student token"));
        if (!Objects.equals(claims.sessionId(), actor.studentSessionId())
                || !claims.rollNumber().equalsIgnoreCase(actor.username())) {
            throw new UnauthorizedException("Student token identity mismatch");
        }
        return claims;
    }

    public record PublicSessionOption(
            long sessionId, String subjectCode, String title, ExamSessionSlot slot,
            Instant scheduledStartAt, Instant scheduledEndAt) {}
    public record StudentLoginRequest(long sessionId, String rollNumber, String accessCode) {}
    public record StudentLoginHttpResponse(
            String signedToken, long sessionId, String rollNumber, String fullName, String assignedVersion,
            Instant scheduledStartAt, Instant effectiveEndAt, double fontScale, boolean highContrast,
            int lastQuestionIndex, Instant serverNow) {}
    public record SaveResponsesRequest(List<CoreStudentExamService.AnswerSubmissionItem> answers, int lastQuestionIndex) {}
    public record HeartbeatRequest(int currentQuestionIndex) {}
    public record FinishRequest(int lastQuestionIndex) {}
}
