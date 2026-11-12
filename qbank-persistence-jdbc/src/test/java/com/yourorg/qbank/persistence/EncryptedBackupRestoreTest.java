package com.yourorg.qbank.persistence;

import com.yourorg.qbank.core.exception.BadRequestException;
import com.yourorg.qbank.core.model.Subject;
import com.yourorg.qbank.core.model.UserRole;
import com.yourorg.qbank.core.port.AuthPort;
import com.yourorg.qbank.core.service.CoreSubjectService;
import com.yourorg.qbank.persistence.adapter.*;
import com.yourorg.qbank.persistence.backup.EncryptedBackupService;
import com.yourorg.qbank.persistence.jdbc.*;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EncryptedBackupRestoreTest {

    @TempDir
    Path tempDir;

    @Test
    void createsEncryptedBackupAndRestoresFullStateAtomicallyWithIdentityReseed() throws Exception {
        DataSource ds = JdbcConnectionPool.create(
                "jdbc:h2:file:" + tempDir.resolve("bakdb").toAbsolutePath() + ";DB_CLOSE_DELAY=-1",
                "sa", "");
        QBankFlywayMigrator.migrate(ds, QBankDialect.H2);
        JdbcTransactionManager tx = new JdbcTransactionManager(ds, QBankDialect.H2);

        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x33);
        var crypto = new AesGcmCryptoAdapter(key, key);
        var json = new JacksonJsonCodecAdapter();
        var storage = new LocalFileStorageAdapter(tempDir.resolve("storage"));
        var clock = new SystemClockAdapter();
        var audit = new JdbcAuditPort(tx, crypto, clock);

        var subjectRepo = new JdbcSubjectRepository(tx);
        var moduleRepo = new JdbcModuleRepository(tx);
        AuthPort adminAuth = () -> Optional.of(new AuthPort.AuthActor("admin", "Admin", Set.of(UserRole.ADMIN)));
        var subjectService = new CoreSubjectService(subjectRepo, moduleRepo, tx, audit, json, clock, adminAuth);

        Subject s1 = subjectService.createSubject("MA101", "Calculus", 120, 1);
        Subject s2 = subjectService.createSubject("PH101", "Physics", 100, 1);
        assertEquals(2L, subjectRepo.countAll());

        var backupService = new EncryptedBackupService(tx, crypto, json, storage, clock, audit);
        EncryptedBackupService.BackupArtifactResult artifact = backupService.createBackup("admin", "preexam", 5);
        assertTrue(artifact.filename().endsWith(".qbank.enc"));
        assertFalse(new String(artifact.encryptedBytes(), StandardCharsets.UTF_8).contains("Calculus"),
                "Encrypted backup archive must not leak plaintext strings");

        // Wrong key rejects inspect/restore
        byte[] wrongKey = new byte[32];
        Arrays.fill(wrongKey, (byte) 0x44);
        var wrongBackupService = new EncryptedBackupService(tx, new AesGcmCryptoAdapter(wrongKey, wrongKey), json, storage, clock, audit);
        assertThrows(BadRequestException.class, () -> wrongBackupService.inspectBackup(artifact.encryptedBytes()));

        // Wipe database rows completely to simulate recovery onto fresh instance
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM qbank_audit_log");
            st.executeUpdate("DELETE FROM qbank_modules");
            st.executeUpdate("DELETE FROM qbank_subjects");
        }
        assertEquals(0L, subjectRepo.countAll());

        // Restore atomically
        var inspected = backupService.restoreFromEncryptedBackup(artifact.encryptedBytes(), "admin");
        assertEquals(2, inspected.rowCounts().get("qbank_subjects"));
        assertEquals(2L, subjectRepo.countAll());
        assertEquals(s1.code(), subjectRepo.findById(s1.id()).orElseThrow().code());
        assertEquals(s2.code(), subjectRepo.findById(s2.id()).orElseThrow().code());

        // Creating another subject AFTER restore must not clash on primary key identity!
        Subject s3 = subjectService.createSubject("CH101", "Chemistry", 80, 1);
        assertTrue(s3.id() > s2.id());
        assertTrue(audit.verifyChain().intact());
    }
}
