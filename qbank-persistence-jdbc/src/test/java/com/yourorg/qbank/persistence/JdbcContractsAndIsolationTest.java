package com.yourorg.qbank.persistence;

import com.yourorg.qbank.core.exception.ConflictException;
import com.yourorg.qbank.core.importer.RawRow;
import com.yourorg.qbank.core.importer.RawSheetData;
import com.yourorg.qbank.core.model.*;
import com.yourorg.qbank.core.port.*;
import com.yourorg.qbank.core.service.*;
import com.yourorg.qbank.persistence.adapter.*;
import com.yourorg.qbank.persistence.jdbc.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class JdbcContractsAndIsolationTest {

    @TempDir
    Path tempDir;

    @Test
    void runsFullLifecycleOnH2AlongsidePreexistingHostTablesAndVerifiesEncryptionAtRest() throws Exception {
        DataSource ds = org.h2.jdbcx.JdbcConnectionPool.create(
                "jdbc:h2:file:" + tempDir.resolve("h2db").toAbsolutePath() + ";DB_CLOSE_DELAY=-1",
                "sa", "");
        // Pre-seed unrelated host table + host Flyway history to prove zero table clash
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE host_users (id BIGINT PRIMARY KEY, username VARCHAR(80) NOT NULL)");
            st.execute("INSERT INTO host_users VALUES (1, 'host_dean')");
            st.execute("CREATE TABLE flyway_schema_history (installed_rank INT PRIMARY KEY)");
        }
        verifyDatabaseFlow(ds, QBankDialect.H2, false);
    }

    @Test
    void runsFullLifecycleOnFileBackedSqlite() throws Exception {
        org.sqlite.SQLiteDataSource ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("qbank.sqlite").toAbsolutePath());
        verifyDatabaseFlow(ds, QBankDialect.SQLITE, true);
    }

    private void verifyDatabaseFlow(DataSource ds, QBankDialect expectedDialect, boolean isSqlite) throws Exception {
        QBankDialect detected = QBankDialect.detect(ds, "auto");
        assertEquals(expectedDialect, detected);
        QBankFlywayMigrator.migrate(ds, detected);

        JdbcTransactionManager tx = new JdbcTransactionManager(ds, detected);
        byte[] aesKey = new byte[32];
        byte[] hmacKey = new byte[32];
        Arrays.fill(aesKey, (byte) 0x2A);
        Arrays.fill(hmacKey, (byte) 0x5B);
        CryptoPort crypto = new AesGcmCryptoAdapter(aesKey, hmacKey);
        JsonCodecPort json = new JacksonJsonCodecAdapter();
        ClockPort clock = new SystemClockAdapter();
        RandomPort random = new SecureRandomAdapter();
        PasswordHasherPort hasher = new BcryptPasswordHasherAdapter(4);
        SpreadsheetPort spreadsheet = new HardenedPoiSpreadsheetAdapter();

        SubjectRepository subjectRepo = new JdbcSubjectRepository(tx);
        ModuleRepository moduleRepo = new JdbcModuleRepository(tx);
        McqRepository mcqRepo = new JdbcMcqRepository(tx);
        ExamRepository examRepo = new JdbcExamRepository(tx, json);
        ImportPreviewRepository previewRepo = new JdbcImportPreviewRepository(tx);
        ExamStudentRepository studentRepo = new JdbcExamStudentRepository(tx);
        StudentResponseRepository responseRepo = new JdbcStudentResponseRepository(tx);
        ExtraTimeGrantRepository grantRepo = new JdbcExtraTimeGrantRepository(tx);
        AuditPort audit = new JdbcAuditPort(tx, crypto, clock);

        final AuthPort.AuthActor[] holder = new AuthPort.AuthActor[1];
        AuthPort auth = () -> Optional.ofNullable(holder[0]);
        DefaultFrozenPaperProvider frozenProvider = new DefaultFrozenPaperProvider(
                examRepo, studentRepo, crypto, json, clock, audit);

        CoreSubjectService subjectService = new CoreSubjectService(subjectRepo, moduleRepo, tx, audit, json, clock, auth);
        CoreImportService importService = new CoreImportService(subjectRepo, moduleRepo, mcqRepo, examRepo, previewRepo,
                spreadsheet, crypto, json, random, clock, tx, audit, e -> {}, auth);
        CoreExamPaperService examService = new CoreExamPaperService(subjectRepo, moduleRepo, mcqRepo, examRepo,
                crypto, json, random, clock, tx, audit, e -> {}, auth, frozenProvider);
        CoreStudentRosterAndInvigilatorService rosterService = new CoreStudentRosterAndInvigilatorService(
                examRepo, studentRepo, responseRepo, grantRepo, hasher, random, clock, tx, audit, json, auth);
        CoreStudentExamService studentService = new CoreStudentExamService(
                examRepo, studentRepo, responseRepo, frozenProvider, responseRepo::upsertResponse,
                hasher, crypto, random, clock, tx, audit, json, auth);
        CoreResultsExportService exportService = new CoreResultsExportService(
                examRepo, studentRepo, responseRepo, frozenProvider, spreadsheet, crypto, json, audit, auth);

        holder[0] = new AuthPort.AuthActor("admin", "Admin", Set.of(UserRole.ADMIN));
        Subject subj = subjectService.createSubject("CS501", "Distributed Systems", 80, 1);

        // Transaction rollback test: duplicate subject throws ConflictException and leaves DB clean
        assertThrows(ConflictException.class, () -> subjectService.createSubject("CS501", "Dup", 80, 1));
        assertEquals(1, subjectRepo.countAll());

        // Real multi-sheet Excel upload -> confirm auto-shuffled & AES-encrypted at rest
        holder[0] = new AuthPort.AuthActor("hod1", "HOD", Set.of(UserRole.HOD));
        byte[] xlsxBytes = buildSampleXlsxWorkbook(3, 80, "SECRET_QUESTION_STEM");
        for (var pr : importService.previewMultiModule(subj.id(), xlsxBytes, "ds.xlsx")) {
            importService.confirmPreview(pr.previewId());
        }

        // Verify plaintext NEVER appears anywhere in raw DB columns
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT text_cipher, options_cipher, correct_cipher FROM qbank_mcqs")) {
            int count = 0;
            while (rs.next()) {
                count++;
                assertFalse(rs.getString("text_cipher").contains("SECRET_QUESTION_STEM"));
                assertFalse(rs.getString("options_cipher").contains("OptionAlpha"));
                assertFalse(rs.getString("correct_cipher").equals("0"));
            }
            assertEquals(240, count);
        }

        // COE generates and freezes exam inside active window
        holder[0] = new AuthPort.AuthActor("coe1", "COE", Set.of(UserRole.COE));
        Instant start = clock.now().minusSeconds(60);
        Instant end = clock.now().plusSeconds(1800);
        var gen = examService.generateExam(subj.id(), "DS Midterm", ExamType.INTERNAL,
                List.of(1, 2, 3), clock.today(), 0, true, start, end, end.plusSeconds(600), end.plusSeconds(3600));
        long sessionId = gen.sessions().get(0).session().id();
        ExamSession frozen = examService.approveAndFreezeSession(sessionId, 1, 0, start, end);

        // Optimistic lock test: second freeze with stale version=0 fails with ConflictException
        assertThrows(ConflictException.class, () -> examService.approveAndFreezeSession(sessionId, 2, 0, start, end));
        assertEquals(SessionStatus.FROZEN, frozen.status());

        // Student sits exam & saves response on SQLite / H2
        var issued = rosterService.registerStudents(sessionId, List.of(
                new CoreStudentRosterAndInvigilatorService.StudentRegistrationRequest(
                        "2026CS01", "Rohan Kulkarni", "HALL-A", 1, 1, 1.0, false, 0)));
        String code = issued.get(0).oneTimeAccessCode();
        String ver = issued.get(0).assignedVersion();

        holder[0] = null;
        var login = studentService.authenticateStudent(sessionId, "2026CS01", code);
        holder[0] = new AuthPort.AuthActor("2026CS01", "Rohan", Set.of(UserRole.STUDENT), sessionId, ver, null);
        var paper = studentService.getActivePaper(sessionId, "2026CS01", login.tokenJti());
        assertEquals(30, paper.questions().size());
        studentService.saveAnswers(sessionId, "2026CS01", login.tokenJti(),
                List.of(new CoreStudentExamService.AnswerSubmissionItem(1, 0, clock.now())), 1);
        studentService.finishExam(sessionId, "2026CS01", login.tokenJti(), 1);

        holder[0] = new AuthPort.AuthActor("coe1", "COE", Set.of(UserRole.COE));
        examService.stopSession(sessionId);

        holder[0] = new AuthPort.AuthActor("admin", "Admin", Set.of(UserRole.ADMIN));
        var export = exportService.exportSessionResults(sessionId);
        assertEquals(1, export.results().size());
        assertEquals(29, export.results().get(0).unansweredCount());
        assertTrue(audit.verifyChain().intact());

        if (!isSqlite) {
            try (Connection c = ds.getConnection(); Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT username FROM host_users WHERE id = 1")) {
                assertTrue(rs.next());
                assertEquals("host_dean", rs.getString(1));
            }
        }
    }

    static byte[] buildSampleXlsxWorkbook(int modules, int perMod, String prefix) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            String[] letters = {"A", "B", "C", "D"};
            for (int m = 1; m <= modules; m++) {
                Sheet s = wb.createSheet("Module " + m);
                Row h = s.createRow(0);
                h.createCell(0).setCellValue("Question");
                h.createCell(1).setCellValue("Option A");
                h.createCell(2).setCellValue("Option B");
                h.createCell(3).setCellValue("Option C");
                h.createCell(4).setCellValue("Option D");
                h.createCell(5).setCellValue("Correct");
                for (int i = 1; i <= perMod; i++) {
                    Row r = s.createRow(i);
                    r.createCell(0).setCellValue(prefix + " M" + m + " Q" + i);
                    r.createCell(1).setCellValue("OptionAlpha " + i);
                    r.createCell(2).setCellValue("OptionBeta " + i);
                    r.createCell(3).setCellValue("OptionGamma " + i);
                    r.createCell(4).setCellValue("OptionDelta " + i);
                    r.createCell(5).setCellValue(letters[(i - 1) % 4]);
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }
}
