package com.yourorg.qbank.starter.web;

import com.yourorg.qbank.core.generator.BlueprintResult;
import com.yourorg.qbank.core.model.*;
import com.yourorg.qbank.core.service.CoreExamPaperService;
import com.yourorg.qbank.core.service.CoreStudentRosterAndInvigilatorService;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("${qbank.base-path:/qbank}/api/v1/coe")
public class QBankCoeController {

    private final CoreExamPaperService examService;
    private final CoreStudentRosterAndInvigilatorService rosterService;

    public QBankCoeController(
            CoreExamPaperService examService,
            CoreStudentRosterAndInvigilatorService rosterService
    ) {
        this.examService = examService;
        this.rosterService = rosterService;
    }

    @PostMapping("/blueprint")
    public BlueprintResult calculateBlueprint(@RequestBody BlueprintRequest req) {
        return examService.calculateBlueprint(req.subjectId(), req.examType(), req.modules(), req.rotationOffset());
    }

    @PostMapping("/exams/generate")
    public CoreExamPaperService.GenerateExamResult generate(@RequestBody GenerateExamRequest req) {
        return examService.generateExam(
                req.subjectId(),
                req.title(),
                req.examType(),
                req.modules(),
                req.examDate(),
                req.rotationOffset(),
                req.includeAfternoon(),
                req.morningStartAt(),
                req.morningEndAt(),
                req.afternoonStartAt(),
                req.afternoonEndAt()
        );
    }

    @PostMapping("/exams/{examId}/afternoon")
    public CoreExamPaperService.SessionWithSetCards addAfternoon(
            @PathVariable long examId,
            @RequestBody WindowRequest req
    ) {
        return examService.addAfternoonSession(examId, req.startAt(), req.endAt());
    }

    @GetMapping("/sessions")
    public List<SessionOverview> listSessions() {
        Map<Long, Exam> examMap = examService.listExams().stream()
                .collect(Collectors.toMap(Exam::id, e -> e));
        List<ExamSession> all = examService.listAllSessions();
        Set<Long> examsWithAfternoon = all.stream()
                .filter(s -> s.sessionSlot() == ExamSessionSlot.AFTERNOON)
                .map(ExamSession::examId)
                .collect(Collectors.toSet());

        return all.stream()
                .map(s -> {
                    Exam e = examMap.get(s.examId());
                    return new SessionOverview(
                            s.id(),
                            s.examId(),
                            e != null ? e.subjectCode() : "",
                            e != null ? e.title() : "",
                            e != null ? e.examType() : null,
                            e != null ? e.examDate() : null,
                            s.sessionSlot(),
                            s.status(),
                            s.chosenSetNumber(),
                            s.scheduledStartAt(),
                            s.scheduledEndAt(),
                            s.version(),
                            examsWithAfternoon.contains(s.examId())
                    );
                })
                .toList();
    }

    @GetMapping("/sessions/{sessionId}/sets")
    public List<SetSummaryCard> listSetCards(@PathVariable long sessionId) {
        return examService.listSetCards(sessionId);
    }

    @GetMapping("/sessions/{sessionId}/sets/{setNumber}/approval-preview")
    public CoreExamPaperService.ApprovalConfirmation approvalPreview(
            @PathVariable long sessionId,
            @PathVariable int setNumber
    ) {
        return examService.getApprovalConfirmation(sessionId, setNumber);
    }

    @PostMapping("/sessions/{sessionId}/approve")
    public ExamSession approve(
            @PathVariable long sessionId,
            @RequestBody ApproveRequest req
    ) {
        return examService.approveAndFreezeSession(
                sessionId, req.setNumber(), req.expectedVersion(), req.scheduledStartAt(), req.scheduledEndAt()
        );
    }

    @PostMapping("/sessions/{sessionId}/start")
    public ExamSession start(@PathVariable long sessionId) {
        return examService.startSession(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/stop")
    public ExamSession stop(@PathVariable long sessionId) {
        return examService.stopSession(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/students")
    public List<CoreStudentRosterAndInvigilatorService.IssuedStudentCredential> registerStudents(
            @PathVariable long sessionId,
            @RequestBody RegisterStudentsRequest req
    ) {
        return rosterService.registerStudents(sessionId, req.students());
    }

    @GetMapping("/sessions/{sessionId}/students")
    public List<CoreStudentRosterAndInvigilatorService.RosterStudentSummary> listRoster(@PathVariable long sessionId) {
        return rosterService.listRosterForCoe(sessionId);
    }

    @PutMapping("/sessions/{sessionId}/students/{rollNumber}/accommodation")
    public CoreStudentRosterAndInvigilatorService.RosterStudentSummary updateAccommodation(
            @PathVariable long sessionId,
            @PathVariable String rollNumber,
            @RequestBody AccommodationRequest req
    ) {
        return rosterService.updateAccommodation(
                sessionId, rollNumber, req.fontScale(), req.highContrast(), req.accommodationExtraMinutes()
        );
    }

    @PostMapping("/sessions/{sessionId}/students/{rollNumber}/reissue-code")
    public CoreStudentRosterAndInvigilatorService.IssuedStudentCredential reissueCode(
            @PathVariable long sessionId,
            @PathVariable String rollNumber
    ) {
        return rosterService.reissueAccessCode(sessionId, rollNumber);
    }

    public record BlueprintRequest(long subjectId, ExamType examType, List<Integer> modules, int rotationOffset) {}
    public record GenerateExamRequest(
            long subjectId, String title, ExamType examType, List<Integer> modules,
            LocalDate examDate, int rotationOffset, boolean includeAfternoon,
            Instant morningStartAt, Instant morningEndAt, Instant afternoonStartAt, Instant afternoonEndAt) {}
    public record WindowRequest(Instant startAt, Instant endAt) {}
    public record ApproveRequest(int setNumber, int expectedVersion, Instant scheduledStartAt, Instant scheduledEndAt) {}
    public record RegisterStudentsRequest(List<CoreStudentRosterAndInvigilatorService.StudentRegistrationRequest> students) {}
    public record AccommodationRequest(double fontScale, boolean highContrast, int accommodationExtraMinutes) {}

    public record SessionOverview(
            long sessionId,
            long examId,
            String subjectCode,
            String title,
            ExamType examType,
            LocalDate examDate,
            ExamSessionSlot slot,
            SessionStatus status,
            Integer chosenSetNumber,
            Instant scheduledStartAt,
            Instant scheduledEndAt,
            int version,
            boolean hasAfternoon
    ) {}
}
