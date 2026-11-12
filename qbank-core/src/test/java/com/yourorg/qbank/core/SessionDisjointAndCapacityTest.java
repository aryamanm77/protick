package com.yourorg.qbank.core;

import com.yourorg.qbank.core.exception.BadRequestException;
import com.yourorg.qbank.core.exception.ForbiddenException;
import com.yourorg.qbank.core.model.*;
import com.yourorg.qbank.core.service.CoreExamPaperService;
import com.yourorg.qbank.core.service.CoreImportService;
import com.yourorg.qbank.core.testkit.InMemoryQBankFixture;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SessionDisjointAndCapacityTest {

    @Test
    void dualSessionSlicesEightDisjointChunksAndLateAfternoonExcludesMorningQuestions() {
        InMemoryQBankFixture fx = new InMemoryQBankFixture();
        fx.asAdmin("admin1");
        Subject subj = fx.subjectService.createSubject("CS401", "Networks", 80, 1);

        // Upload 80 questions in M1, M2, M3 (INTERNAL with 3 modules => 10 questions/module -> 8 * 10 = 80 exact fit!)
        fx.asHod("hod1");
        byte[] bytes = fx.spreadsheetPort.stageWorkbook("net.xlsx", InMemoryQBankFixture.makeStandardModuleSheets(3, 80));
        for (var pr : fx.importService.previewMultiModule(subj.id(), bytes, "net.xlsx")) {
            fx.importService.confirmPreview(pr.previewId());
        }

        // HOD cannot generate papers
        assertThrows(ForbiddenException.class, () -> fx.examPaperService.generateExam(
                subj.id(), "Blocked", ExamType.INTERNAL, List.of(1, 2, 3), fx.clock.today(), 0,
                true, fx.clock.now(), fx.clock.now().plusSeconds(3600),
                fx.clock.now().plusSeconds(7200), fx.clock.now().plusSeconds(10800)));

        // COE generates dual session (MORNING + AFTERNOON)
        fx.asCoe("coe1");
        Instant mStart = fx.clock.now().plusSeconds(600);
        Instant mEnd = mStart.plusSeconds(3600);
        Instant aStart = mEnd.plusSeconds(3600);
        Instant aEnd = aStart.plusSeconds(3600);

        CoreExamPaperService.GenerateExamResult dual = fx.examPaperService.generateExam(
                subj.id(), "Networks IA1", ExamType.INTERNAL, List.of(1, 2, 3),
                fx.clock.today(), 0, true, mStart, mEnd, aStart, aEnd);

        assertEquals(2, dual.sessions().size());
        long mSessionId = dual.sessions().get(0).session().id();
        long aSessionId = dual.sessions().get(1).session().id();

        // Collect ALL 4 morning sets + ALL 4 afternoon sets = 8 * 30 = 240 question IDs -> all must be pairwise disjoint!
        Set<String> seenAcrossBothSessions = new HashSet<>();
        for (long sid : List.of(mSessionId, aSessionId)) {
            List<PaperSetStored> sets = fx.examRepo.findSetsBySessionId(sid);
            assertEquals(4, sets.size());
            for (PaperSetStored s : sets) {
                assertEquals(30, s.questionCount());
                for (String qid : s.questionIds()) {
                    assertTrue(seenAcrossBothSessions.add(qid), "Duplicate question across 8 chunks: " + qid);
                }
            }
        }
        assertEquals(240, seenAcrossBothSessions.size());

        // Now test Capacity Blocking when pool < 8 * k (2 modules => 15 each => needs 120, only 80 available!)
        BadRequestException capEx = assertThrows(BadRequestException.class, () -> fx.examPaperService.generateExam(
                subj.id(), "Too Big Dual", ExamType.INTERNAL, List.of(1, 2),
                fx.clock.today(), 0, true, mStart, mEnd, aStart, aEnd));
        assertTrue(capEx.getMessage().contains("Module 1 has 80 questions, needs 120"));

        // Create morning-only with M1 & M2 (needs 4 * 15 = 60 <= 80 -> succeeds!), then adding afternoon fails (20 left < 60)!
        CoreExamPaperService.GenerateExamResult mOnly = fx.examPaperService.generateExam(
                subj.id(), "Morning Only", ExamType.INTERNAL, List.of(1, 2),
                fx.clock.today(), 0, false, mStart, mEnd, null, null);
        long onlyMorningExamId = mOnly.exam().id();
        BadRequestException lateAftEx = assertThrows(BadRequestException.class, () ->
                fx.examPaperService.addAfternoonSession(onlyMorningExamId, aStart, aEnd));
        assertTrue(lateAftEx.getMessage().contains("20 unused questions"));

        // But with 3 modules (10 per set -> morning uses 40, leaving 40 unused -> late afternoon also needs 40 -> exact fit!)
        CoreExamPaperService.GenerateExamResult m3Only = fx.examPaperService.generateExam(
                subj.id(), "Late Afternoon Exact", ExamType.INTERNAL, List.of(1, 2, 3),
                fx.clock.today(), 0, false, mStart, mEnd, null, null);
        var lateAft = fx.examPaperService.addAfternoonSession(m3Only.exam().id(), aStart, aEnd);
        Set<String> mUsed = fx.examRepo.findQuestionIdsUsedInSession(m3Only.sessions().get(0).session().id());
        Set<String> aUsed = fx.examRepo.findQuestionIdsUsedInSession(lateAft.session().id());
        assertEquals(120, mUsed.size());
        assertEquals(120, aUsed.size());
        for (String id : aUsed) {
            assertFalse(mUsed.contains(id), "Late afternoon must exclude every morning question ID");
        }
    }

    @Test
    void frozenPaperProviderEnforcesTimeWindowAndDetectsTampering() {
        InMemoryQBankFixture fx = new InMemoryQBankFixture();
        fx.asAdmin("admin1");
        Subject subj = fx.subjectService.createSubject("CS402", "Compiler", 60, 1);

        fx.asHod("hod1");
        byte[] bytes = fx.spreadsheetPort.stageWorkbook("comp.xlsx", InMemoryQBankFixture.makeStandardModuleSheets(5, 45));
        for (var pr : fx.importService.previewMultiModule(subj.id(), bytes, "comp.xlsx")) {
            fx.importService.confirmPreview(pr.previewId());
        }

        fx.asCoe("coe1");
        Instant start = fx.clock.now().plusSeconds(300);
        Instant end = start.plusSeconds(3600);
        var gen = fx.examPaperService.generateExam(
                subj.id(), "Compiler SEE", ExamType.SEE, List.of(1, 2, 3, 4, 5),
                fx.clock.today(), 0, false, start, end, null, null);
        long sid = gen.sessions().get(0).session().id();
        fx.examPaperService.approveAndFreezeSession(sid, 2, 0, start, end);

        // Before scheduled start -> Forbidden!
        assertThrows(ForbiddenException.class, () -> fx.frozenPaperProvider.version(sid, "A"));
        assertThrows(ForbiddenException.class, () -> fx.frozenPaperProvider.key(sid, "A"));

        // Move clock into active window -> decrypts cleanly & matches all 50 questions across versions with zero positional collision
        fx.clock.set(start.plusSeconds(10));
        FrozenVersionView vA = fx.frozenPaperProvider.version(sid, "A");
        FrozenVersionView vB = fx.frozenPaperProvider.version(sid, "B");
        assertEquals(50, vA.questions().size());
        assertEquals(50, vB.questions().size());
        for (int i = 0; i < 50; i++) {
            assertNotEquals(vA.questions().get(i).mcqId(), vB.questions().get(i).mcqId(),
                    "Position " + (i + 1) + " should not repeat across versions A and B");
        }

        // Advance clock past scheduled end -> blocked again!
        fx.clock.set(end.plusSeconds(1));
        assertThrows(ForbiddenException.class, () -> fx.frozenPaperProvider.version(sid, "A"));

        // Tamper version hash while inside window -> tamper detected!
        fx.clock.set(start.plusSeconds(20));
        fx.frozenPaperProvider.evictSession(sid);
        PaperSetStored chosenSet = fx.examRepo.findSetsBySessionId(sid).stream()
                .filter(s -> s.status() == PaperSetStatus.SELECTED).findFirst().orElseThrow();
        fx.examRepo.tamperVersionHash(chosenSet.id(), "A", "0".repeat(64));
        assertThrows(ForbiddenException.class, () -> fx.frozenPaperProvider.version(sid, "A"));
        assertTrue(fx.auditPort.entries.stream().anyMatch(e -> "PAPER_INTEGRITY_TAMPER_ALERT".equals(e.eventType())));
    }
}
