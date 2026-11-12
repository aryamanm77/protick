package com.yourorg.qbank.core;

import com.yourorg.qbank.core.exception.BadRequestException;
import com.yourorg.qbank.core.exception.ConflictException;
import com.yourorg.qbank.core.exception.ForbiddenException;
import com.yourorg.qbank.core.exception.NotFoundException;
import com.yourorg.qbank.core.importer.ImportPreviewResult;
import com.yourorg.qbank.core.importer.RawRow;
import com.yourorg.qbank.core.importer.RawSheetData;
import com.yourorg.qbank.core.model.EncryptedMcq;
import com.yourorg.qbank.core.model.ModuleStatus;
import com.yourorg.qbank.core.model.ModuleUnit;
import com.yourorg.qbank.core.model.Subject;
import com.yourorg.qbank.core.security.QBankAad;
import com.yourorg.qbank.core.service.CoreImportService;
import com.yourorg.qbank.core.testkit.InMemoryQBankFixture;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AutoShuffleImportTest {

    @Test
    void previewPreservesOriginalSheetOrderThenConfirmAutoShufflesEncryptsAndDeletesPreview() {
        InMemoryQBankFixture fx = new InMemoryQBankFixture();
        fx.asAdmin("admin1");
        Subject subj = fx.subjectService.createSubject("CS201", "Algorithms", 50, 1);

        fx.asHod("hod1");
        RawSheetData sheet = InMemoryQBankFixture.makeSingleModuleSheet("Module 1", 1, 25);
        byte[] bytes = fx.spreadsheetPort.stageWorkbook("cs201_m1.xlsx", List.of(sheet));

        ImportPreviewResult preview = fx.importService.previewSingleModule(subj.id(), 1, bytes, "cs201_m1.xlsx");
        assertEquals(25, preview.validCount());
        // Preview order strictly equals sheet source order (rows 2..26)
        for (int i = 0; i < 25; i++) {
            assertEquals(i + 2, preview.rows().get(i).getSourceRow());
            assertTrue(preview.rows().get(i).getQuestionText().contains("Question #" + (i + 1) + " "));
        }

        // COE cannot upload or confirm!
        fx.asCoe("coe1");
        assertThrows(ForbiddenException.class, () -> fx.importService.confirmPreview(preview.previewId()));
        assertThrows(ForbiddenException.class, () -> fx.importService.previewSingleModule(subj.id(), 1, bytes, "cs201_m1.xlsx"));

        // Confirm as HOD
        fx.asHod("hod1");
        CoreImportService.ConfirmImportResult result = fx.importService.confirmPreview(preview.previewId());
        assertEquals(ModuleStatus.READY, result.status());
        assertEquals(25, result.questionCount());

        // Preview deleted immediately after confirm so HOD can never inspect shuffled order or question text again
        assertThrows(NotFoundException.class, () -> fx.importService.getPreview(preview.previewId()));

        // Verify stored MCQs are encrypted at rest and shuffled away from sheet order, with remapped options intact
        List<EncryptedMcq> stored = fx.mcqRepo.findBySubjectAndModule(subj.id(), 1);
        assertEquals(25, stored.size());
        List<Integer> storedSourceRows = new ArrayList<>();
        for (int i = 0; i < stored.size(); i++) {
            EncryptedMcq enc = stored.get(i);
            assertEquals("CS201-M1-" + String.format("%03d", i + 1), enc.id());
            assertNotEquals("M1 Question", enc.textEnc().cipherBase64());
            String decryptedText = fx.crypto.decrypt(enc.textEnc(), QBankAad.mcqText(subj.id(), 1, enc.id()));
            assertFalse(decryptedText.isBlank());
            List<String> decryptedOpts = fx.json.fromJsonStringList(
                    fx.crypto.decrypt(enc.optionsEnc(), QBankAad.mcqOptions(subj.id(), 1, enc.id())));
            int decryptedCorrect = Integer.parseInt(
                    fx.crypto.decrypt(enc.correctIndexEnc(), QBankAad.mcqCorrectIndex(subj.id(), 1, enc.id())));

            // Map back to original row to verify option text semantic equality despite shuffled option positions
            int origIdx = enc.sourceRow() - 2;
            String origCorrectLetter = switch (origIdx % 4) {
                case 0 -> "Alpha";
                case 1 -> "Beta";
                case 2 -> "Gamma";
                default -> "Delta";
            };
            assertTrue(decryptedOpts.get(decryptedCorrect).endsWith(origCorrectLetter),
                    "Correct index must follow shuffled option text");
            storedSourceRows.add(enc.sourceRow());
        }

        // Must differ from sequential [2..26]
        List<Integer> originalRows = new ArrayList<>();
        for (int r = 2; r <= 26; r++) originalRows.add(r);
        assertNotEquals(originalRows, storedSourceRows, "Bank order must be auto-shuffled on confirm");
    }

    @Test
    void confirmsBlockOnRowErrorsAndUnlockAfterFixOrExclude() {
        InMemoryQBankFixture fx = new InMemoryQBankFixture();
        fx.asAdmin("admin1");
        Subject subj = fx.subjectService.createSubject("CS202", "DBMS", 10, 1);

        fx.asHod("hod1");
        RawSheetData badSheet = new RawSheetData("Module 1", 6, List.of(
                new RawRow(1, List.of("Question", "Option A", "Option B", "Option C", "Option D", "Correct")),
                new RawRow(2, List.of("Q1 stem?", "A1", "B1", "C1", "D1", "")),
                new RawRow(3, List.of("Q2 stem?", "A2", "B2", "C2", "D2", "C"))
        ), Set.of());
        byte[] bytes = fx.spreadsheetPort.stageWorkbook("bad.xlsx", List.of(badSheet));

        ImportPreviewResult p = fx.importService.previewSingleModule(subj.id(), 1, bytes, "bad.xlsx");
        assertEquals(1, p.errorCount());
        assertFalse(p.canConfirmDirectly());
        assertTrue(p.issues().stream().anyMatch(i -> i.message().startsWith("Row 2:")));
        assertThrows(BadRequestException.class, () -> fx.importService.confirmPreview(p.previewId()));

        // Fix row 2 by selecting correctIndex=1
        ImportPreviewResult fixed = fx.importService.updatePreviewRow(p.previewId(), 2, null, null, 1, false);
        assertEquals(0, fixed.errorCount());
        assertEquals(2, fixed.validCount());
        assertTrue(fixed.canConfirmDirectly());

        CoreImportService.ConfirmImportResult res = fx.importService.confirmPreview(p.previewId());
        assertEquals(2, res.questionCount());
    }

    @Test
    void blocksReimportWhileFrozenOrLiveSessionUsesModule() {
        InMemoryQBankFixture fx = new InMemoryQBankFixture();
        fx.asAdmin("admin1");
        Subject subj = fx.subjectService.createSubject("CS301", "OS", 20, 1);

        fx.asHod("hod1");
        byte[] bytes = fx.spreadsheetPort.stageWorkbook("full.xlsx", InMemoryQBankFixture.makeStandardModuleSheets(5, 60));
        for (ImportPreviewResult pr : fx.importService.previewMultiModule(subj.id(), bytes, "full.xlsx")) {
            fx.importService.confirmPreview(pr.previewId());
        }

        fx.asCoe("coe1");
        Instant start = fx.clock.now().plusSeconds(60);
        Instant end = start.plusSeconds(3600);
        var gen = fx.examPaperService.generateExam(
                subj.id(), "OS Test", com.yourorg.qbank.core.model.ExamType.INTERNAL,
                List.of(1, 2), fx.clock.today(), 0, false, start, end, null, null);
        long morningId = gen.sessions().get(0).session().id();
        fx.examPaperService.approveAndFreezeSession(morningId, 1, 0, start, end);

        fx.asHod("hod1");
        byte[] mod1 = fx.spreadsheetPort.stageWorkbook("reimport.xlsx", List.of(InMemoryQBankFixture.makeSingleModuleSheet("Module 1", 1, 20)));
        ImportPreviewResult p2 = fx.importService.previewSingleModule(subj.id(), 1, mod1, "reimport.xlsx");
        assertThrows(ConflictException.class, () -> fx.importService.confirmPreview(p2.previewId()));
    }

    @Test
    void halfModuleSheetNamesParseAndAssignPartSpecificIds() {
        assertEquals(Integer.valueOf(1), ModuleUnit.parseSheetUnit("Module 1"));
        assertEquals(Integer.valueOf(21), ModuleUnit.parseSheetUnit("Module 2A"));
        assertEquals(Integer.valueOf(21), ModuleUnit.parseSheetUnit("Module 2 (1st Half)"));
        assertEquals(Integer.valueOf(32), ModuleUnit.parseSheetUnit("Module 3 Part 2"));
        assertEquals(Integer.valueOf(42), ModuleUnit.parseSheetUnit("M4.2"));
        assertEquals(Integer.valueOf(52), ModuleUnit.parseSheetUnit("M5B"));
        assertFalse(ModuleUnit.overlaps(21, 22));
        assertTrue(ModuleUnit.overlaps(2, 21));
        assertTrue(ModuleUnit.overlaps(21, 2));

        InMemoryQBankFixture fx = new InMemoryQBankFixture();
        fx.asAdmin("admin1");
        Subject subj = fx.subjectService.createSubject("CS209", "HalfBank", 120, 1);

        fx.asHod("hod1");
        byte[] bytes = fx.spreadsheetPort.stageWorkbook("m2a.xlsx", List.of(InMemoryQBankFixture.makeSingleModuleSheet("Module 2 Part 1", 21, 15)));
        ImportPreviewResult prev = fx.importService.previewSingleModule(subj.id(), 21, bytes, "m2a.xlsx");
        assertEquals(60, prev.expectedCount());
        assertEquals(15, prev.validCount());
        fx.importService.confirmPreview(prev.previewId());

        List<EncryptedMcq> stored = fx.mcqRepo.findBySubjectAndModule(subj.id(), 21);
        assertEquals(15, stored.size());
        assertEquals("CS209-M2A-001", stored.get(0).id());
        String decrypted = fx.crypto.decrypt(stored.get(0).textEnc(), QBankAad.mcqText(subj.id(), 21, stored.get(0).id()));
        assertFalse(decrypted.isBlank());
    }
}
