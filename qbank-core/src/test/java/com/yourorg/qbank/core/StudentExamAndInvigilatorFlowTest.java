package com.yourorg.qbank.core;

import com.yourorg.qbank.core.exception.ConflictException;
import com.yourorg.qbank.core.exception.ForbiddenException;
import com.yourorg.qbank.core.exception.RateLimitException;
import com.yourorg.qbank.core.exception.UnauthorizedException;
import com.yourorg.qbank.core.model.ExamType;
import com.yourorg.qbank.core.model.StudentLoginState;
import com.yourorg.qbank.core.model.Subject;
import com.yourorg.qbank.core.service.CoreResultsExportService;
import com.yourorg.qbank.core.service.CoreStudentExamService;
import com.yourorg.qbank.core.service.CoreStudentRosterAndInvigilatorService;
import com.yourorg.qbank.core.testkit.InMemoryQBankFixture;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StudentExamAndInvigilatorFlowTest {

    @Test
    void fullEndToEndStudentSessionCrashRecoveryExtraTimeAndSanitizedExport() {
        InMemoryQBankFixture fx = new InMemoryQBankFixture();

        // 1. Setup Admin + Accounts + Lockout check
        assertTrue(fx.authService.isSetupRequired());
        fx.authService.setupInitialAdmin("rootadmin", "Root Admin", "StrongPass#123");
        assertFalse(fx.authService.isSetupRequired());
        assertThrows(ConflictException.class, () -> fx.authService.setupInitialAdmin("second", "Second", "StrongPass#123"));

        // Rate-limit brute force login lock check
        for (int i = 0; i < 4; i++) {
            assertThrows(UnauthorizedException.class, () -> fx.authService.login("rootadmin", "WrongPass99"));
        }
        assertThrows(RateLimitException.class, () -> fx.authService.login("rootadmin", "WrongPass99"));
        assertThrows(RateLimitException.class, () -> fx.authService.login("rootadmin", "StrongPass#123"));

        // Advance clock past 15-min lock -> login succeeds
        fx.clock.advanceSeconds(16 * 60);
        var adminLogin = fx.authService.login("rootadmin", "StrongPass#123");
        assertNotNull(adminLogin.token());

        // 2. Subject + HOD auto-shuffled import + COE generation & freeze
        fx.asAdmin("rootadmin");
        Subject subj = fx.subjectService.createSubject("EC101", "Digital Electronics", 50, 2);

        fx.asHod("hod_ec");
        byte[] wb = fx.spreadsheetPort.stageWorkbook("ec.xlsx", InMemoryQBankFixture.makeStandardModuleSheets(3, 50));
        for (var pr : fx.importService.previewMultiModule(subj.id(), wb, "ec.xlsx")) {
            fx.importService.confirmPreview(pr.previewId());
        }

        fx.asCoe("coe_ec");
        Instant start = fx.clock.now().plusSeconds(120);
        Instant end = start.plusSeconds(1800); // 30 mins
        var gen = fx.examPaperService.generateExam(
                subj.id(), "EC IA1", ExamType.INTERNAL, List.of(1, 2, 3),
                fx.clock.today(), 0, false, start, end, null, null);
        long sessionId = gen.sessions().get(0).session().id();
        fx.examPaperService.approveAndFreezeSession(sessionId, 1, 0, start, end);

        // Register 2 students (R101) including formula-injection-like test name & accommodations
        var creds = fx.rosterService.registerStudents(sessionId, List.of(
                new CoreStudentRosterAndInvigilatorService.StudentRegistrationRequest(
                        "EC26-001", "Alice Rao", "R101", 1, 1, 1.25, true, 10),
                new CoreStudentRosterAndInvigilatorService.StudentRegistrationRequest(
                        "EC26-002", "=EvilFormula", "R102", 1, 2, 1.0, false, 0)
        ));
        String code1 = creds.get(0).oneTimeAccessCode();
        String ver1 = creds.get(0).assignedVersion();

        // Student cannot login BEFORE scheduled start time
        fx.clearAuth();
        assertThrows(ForbiddenException.class, () -> fx.studentExamService.authenticateStudent(sessionId, "EC26-001", code1));

        // Open exam window & COE starts session
        fx.clock.set(start);
        fx.asCoe("coe_ec");
        fx.examPaperService.startSession(sessionId);

        // 3. Student 1 logs in, fetches paper (sanitized: zero mcqId/module/answer)
        fx.clearAuth();
        var sLogin = fx.studentExamService.authenticateStudent(sessionId, "EC26-001", code1);
        fx.asStudent("EC26-001", sessionId, ver1);
        var paper = fx.studentExamService.getActivePaper(sessionId, "EC26-001", sLogin.tokenJti());
        assertEquals(15, paper.questions().size());
        assertTrue(paper.highContrast());
        assertEquals(1.25, paper.fontScale());
        assertEquals(end.plusSeconds(600), paper.effectiveEndAt());

        // Save answers on PC #1
        fx.studentExamService.saveAnswers(sessionId, "EC26-001", sLogin.tokenJti(), List.of(
                new CoreStudentExamService.AnswerSubmissionItem(1, 2, fx.clock.now()),
                new CoreStudentExamService.AnswerSubmissionItem(2, 0, fx.clock.now())
        ), 2);

        // Second login blocked while session active!
        assertThrows(ConflictException.class, () -> fx.studentExamService.authenticateStudent(sessionId, "EC26-001", code1));

        // Room isolation: R102 invigilator cannot reset R101 student!
        fx.asInvigilator("inv_r102", "R102");
        assertThrows(ForbiddenException.class, () -> fx.rosterService.resetStudentLogin(sessionId, "EC26-001", "PC died"));

        // R101 invigilator resets EC26-001 login & grants +5 min extra time
        fx.asInvigilator("inv_r101", "R101");
        var monitor = fx.rosterService.getRoomMonitor(sessionId, "R101");
        assertEquals(1, monitor.totalStudents());
        assertEquals(StudentLoginState.ACTIVE, monitor.students().get(0).observedState());
        assertEquals(2, monitor.students().get(0).answeredCount());

        fx.rosterService.resetStudentLogin(sessionId, "EC26-001", "Workstation PSU failed");
        fx.rosterService.grantExtraTime(sessionId, "R101", "EC26-001", 5, "Workstation reboot delay");

        // Old token JTI is now rejected!
        fx.asStudent("EC26-001", sessionId, ver1);
        assertThrows(UnauthorizedException.class, () -> fx.studentExamService.getActivePaper(sessionId, "EC26-001", sLogin.tokenJti()));

        // Student logs in cleanly on spare PC #2 and sees answers + lastQuestionIndex + extra time (+15m total)!
        var resumeLogin = fx.studentExamService.authenticateStudent(sessionId, "EC26-001", code1);
        assertEquals(2, resumeLogin.lastQuestionIndex());
        var resumedPaper = fx.studentExamService.getActivePaper(sessionId, "EC26-001", resumeLogin.tokenJti());
        assertEquals(2, resumedPaper.savedSelections().get(1));
        assertEquals(0, resumedPaper.savedSelections().get(2));
        assertEquals(end.plusSeconds(900), resumedPaper.effectiveEndAt());

        fx.studentExamService.finishExam(sessionId, "EC26-001", resumeLogin.tokenJti(), 15);

        // Stop session & export formula-sanitized results as ADMIN
        fx.asCoe("coe_ec");
        fx.examPaperService.stopSession(sessionId);

        fx.asAdmin("rootadmin");
        CoreResultsExportService.SessionResultsExport exp = fx.resultsExportService.exportSessionResults(sessionId);
        assertEquals(2, exp.results().size());
        String csv = new String(exp.csvBytes(), StandardCharsets.UTF_8);
        assertTrue(csv.contains("'=EvilFormula"), "Formula cell must be neutralized in CSV export");
        assertTrue(fx.auditService.verifyChain().intact());
    }
}
