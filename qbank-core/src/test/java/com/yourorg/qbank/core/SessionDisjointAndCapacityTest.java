package com.yourorg.qbank.core;

import com.yourorg.qbank.core.exception.BadRequestException;
import com.yourorg.qbank.core.exception.ForbiddenException;
import com.yourorg.qbank.core.importer.ImportPreviewResult;
import com.yourorg.qbank.core.importer.RawSheetData;
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

    @Test
    void halfModuleProportionalBlueprintAndLockIsolationAndSeeMerge() {
        InMemoryQBankFixture fx = new InMemoryQBankFixture();
        fx.asAdmin("admin1");
        Subject subj = fx.subjectService.createSubject("CS405", "Cloud Systems", 120, 1);

        // HOD uploads M1 (80 Qs), M2 1st Half (50 Qs), M3..M5 (80 Qs each) using mixed multi-sheet names
        fx.asHod("hod1");
        List<RawSheetData> mixedSheets = List.of(
                InMemoryQBankFixture.makeSingleModuleSheet("Module 1", 1, 80),
                InMemoryQBankFixture.makeSingleModuleSheet("Module 2 (1st Half)", 21, 50),
                InMemoryQBankFixture.makeSingleModuleSheet("Module 3", 3, 80),
                InMemoryQBankFixture.makeSingleModuleSheet("Module 4", 4, 80),
                InMemoryQBankFixture.makeSingleModuleSheet("Module 5", 5, 80)
        );
        byte[] bytes = fx.spreadsheetPort.stageWorkbook("mixed.xlsx", mixedSheets);
        List<ImportPreviewResult> previews = fx.importService.previewMultiModule(subj.id(), bytes, "mixed.xlsx");
        assertEquals(5, previews.size());
        var m2HalfPreview = previews.stream().filter(p -> p.moduleNumber() == 21).findFirst().orElseThrow();
        assertEquals(60, m2HalfPreview.expectedCount());
        for (var pr : previews) {
            fx.importService.confirmPreview(pr.previewId());
        }

        // COE verifies proportional blueprint math
        fx.asCoe("coe1");
        var bp15 = fx.examPaperService.calculateBlueprint(subj.id(), ExamType.INTERNAL, List.of(1, 21), 0);
        assertEquals(30, bp15.totalQuestions());
        assertEquals(Map.of(1, 20, 21, 10), bp15.moduleCounts());

        var bp25 = fx.examPaperService.calculateBlueprint(subj.id(), ExamType.INTERNAL, List.of(1, 2, 31), 0);
        assertEquals(Map.of(1, 12, 2, 12, 31, 6), bp25.moduleCounts());

        var bp35Rot0 = fx.examPaperService.calculateBlueprint(subj.id(), ExamType.INTERNAL, List.of(1, 2, 3, 41), 0);
        assertEquals(Map.of(1, 9, 2, 9, 3, 8, 41, 4), bp35Rot0.moduleCounts());
        var bp35Rot1 = fx.examPaperService.calculateBlueprint(subj.id(), ExamType.INTERNAL, List.of(1, 2, 3, 41), 1);
        assertEquals(Map.of(1, 8, 2, 9, 3, 9, 41, 4), bp35Rot1.moduleCounts());

        assertThrows(IllegalArgumentException.class, () ->
                fx.examPaperService.calculateBlueprint(subj.id(), ExamType.INTERNAL, List.of(2, 21), 0));

        // Generate and freeze 1.5-module IA-1 (M1 Full + M2 1st Half)
        Instant start = fx.clock.now().plusSeconds(300);
        Instant end = start.plusSeconds(3600);
        var ia1 = fx.examPaperService.generateExam(
                subj.id(), "IA-1 1.5 Modules", ExamType.INTERNAL, List.of(1, 21),
                fx.clock.today(), 0, false, start, end, null, null);
        long ia1Sid = ia1.sessions().get(0).session().id();
        fx.examPaperService.approveAndFreezeSession(ia1Sid, 1, 0, start, end);

        List<PaperSetStored> ia1Sets = fx.examRepo.findSetsBySessionId(ia1Sid);
        assertEquals(4, ia1Sets.size());
        Set<String> allIa1Ids = new HashSet<>();
        for (PaperSetStored s : ia1Sets) {
            assertEquals(30, s.questionCount());
            long m1Count = s.questionIds().stream().filter(id -> id.startsWith("CS405-M1-")).count();
            long m2aCount = s.questionIds().stream().filter(id -> id.startsWith("CS405-M2A-")).count();
            assertEquals(20, m1Count);
            assertEquals(10, m2aCount);
            for (String qid : s.questionIds()) {
                assertTrue(allIa1Ids.add(qid));
            }
        }

        // Lock isolation: M2 1st Half (21) and M2 Full (2) are blocked, while M2 2nd Half (22) MUST succeed!
        fx.asHod("hod1");
        byte[] lockTestA = fx.spreadsheetPort.stageWorkbook("m2a.xlsx", List.of(InMemoryQBankFixture.makeSingleModuleSheet("Module 2A", 21, 50)));
        var pBlockedA = fx.importService.previewSingleModule(subj.id(), 21, lockTestA, "m2a.xlsx");
        assertThrows(com.yourorg.qbank.core.exception.ConflictException.class, () -> fx.importService.confirmPreview(pBlockedA.previewId()));

        byte[] lockTestFull = fx.spreadsheetPort.stageWorkbook("m2f.xlsx", List.of(InMemoryQBankFixture.makeSingleModuleSheet("Module 2", 2, 100)));
        var pBlockedFull = fx.importService.previewSingleModule(subj.id(), 2, lockTestFull, "m2f.xlsx");
        assertThrows(com.yourorg.qbank.core.exception.ConflictException.class, () -> fx.importService.confirmPreview(pBlockedFull.previewId()));

        byte[] m2bBytes = fx.spreadsheetPort.stageWorkbook("m2b.xlsx", List.of(InMemoryQBankFixture.makeSingleModuleSheet("Module 2B", 22, 50)));
        var p2b = fx.importService.previewSingleModule(subj.id(), 22, m2bBytes, "m2b.xlsx");
        var res2b = fx.importService.confirmPreview(p2b.previewId());
        assertEquals(50, res2b.questionCount());

        // Now COE runs SEE across M1..M5 -> Module 2 automatically merges M2A (50) + M2B (50) = 100 Qs (>= 40 needed)!
        fx.asCoe("coe1");
        Instant seeStart = end.plusSeconds(3600);
        Instant seeEnd = seeStart.plusSeconds(5400);
        var see = fx.examPaperService.generateExam(
                subj.id(), "Cloud SEE", ExamType.SEE, List.of(1, 2, 3, 4, 5),
                fx.clock.today().plusDays(7), 0, false, seeStart, seeEnd, null, null);
        long seeSid = see.sessions().get(0).session().id();
        fx.examPaperService.approveAndFreezeSession(seeSid, 1, 0, seeStart, seeEnd);

        // Once SEE freezes Full M2 (drawing from 21+22), M2B (22) is also locked!
        fx.asHod("hod1");
        var p2bRetry = fx.importService.previewSingleModule(subj.id(), 22, m2bBytes, "m2b.xlsx");
        assertThrows(com.yourorg.qbank.core.exception.ConflictException.class, () -> fx.importService.confirmPreview(p2bRetry.previewId()));
    }
}
