package com.yourorg.qbank.persistence;

import com.yourorg.qbank.core.model.AuditVerificationResult;
import com.yourorg.qbank.persistence.adapter.AesGcmCryptoAdapter;
import com.yourorg.qbank.persistence.adapter.SystemClockAdapter;
import com.yourorg.qbank.persistence.jdbc.JdbcAuditPort;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class JdbcAuditTamperDetectionTest {

    @TempDir
    Path tempDir;

    @Test
    void detectsDirectSqlTamperingInHashChainedAuditLog() throws Exception {
        DataSource ds = JdbcConnectionPool.create(
                "jdbc:h2:file:" + tempDir.resolve("auditdb").toAbsolutePath() + ";DB_CLOSE_DELAY=-1",
                "sa", "");
        QBankFlywayMigrator.migrate(ds, QBankDialect.H2);
        JdbcTransactionManager tx = new JdbcTransactionManager(ds, QBankDialect.H2);
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        var crypto = new AesGcmCryptoAdapter(key, key);
        var audit = new JdbcAuditPort(tx, crypto, new SystemClockAdapter());

        audit.append("LOGIN_SUCCESS", "admin", "ADMIN", "USER", "1", "{}");
        audit.append("SUBJECT_CREATED", "admin", "ADMIN", "SUBJECT", "CS101", "{}");
        audit.append("PAPER_FROZEN", "coe", "COE", "SESSION", "1", "{\"set\":1}");

        AuditVerificationResult intact = audit.verifyChain();
        assertTrue(intact.intact());
        assertEquals(3L, intact.totalChecked());

        // Bypass application and tamper row 2 directly via SQL
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE qbank_audit_log SET actor_username = 'attacker' WHERE seq_no = 2");
        }

        AuditVerificationResult broken = audit.verifyChain();
        assertFalse(broken.intact());
        assertEquals(2L, broken.firstBrokenSeqNo());
    }
}
