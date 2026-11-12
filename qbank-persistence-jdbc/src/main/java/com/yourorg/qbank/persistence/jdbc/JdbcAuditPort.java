package com.yourorg.qbank.persistence.jdbc;

import com.yourorg.qbank.core.model.AuditLogEntry;
import com.yourorg.qbank.core.model.AuditVerificationResult;
import com.yourorg.qbank.core.port.AuditPort;
import com.yourorg.qbank.core.port.ClockPort;
import com.yourorg.qbank.core.port.CryptoPort;
import com.yourorg.qbank.core.security.AuditChainCalculator;
import com.yourorg.qbank.persistence.JdbcTransactionManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only hash-chained JDBC audit log implementation.
 */
public class JdbcAuditPort implements AuditPort {

    private final JdbcTransactionManager tx;
    private final CryptoPort cryptoPort;
    private final ClockPort clockPort;
    private final Object appendLock = new Object();

    public JdbcAuditPort(JdbcTransactionManager tx, CryptoPort cryptoPort, ClockPort clockPort) {
        this.tx = tx;
        this.cryptoPort = cryptoPort;
        this.clockPort = clockPort;
    }

    @Override
    public AuditLogEntry append(
            String eventType,
            String actorUsername,
            String actorRole,
            String entityType,
            String entityId,
            String detailsJson
    ) {
        synchronized (appendLock) {
            return tx.inTransaction(() -> tx.withConnection(conn -> {
                long prevSeq = 0L;
                String prevHash = AuditChainCalculator.GENESIS_PREV_HASH;
                try (PreparedStatement tail = conn.prepareStatement(
                        "SELECT seq_no, entry_hash FROM qbank_audit_log ORDER BY seq_no DESC LIMIT 1");
                     ResultSet rs = tail.executeQuery()) {
                    if (rs.next()) {
                        prevSeq = rs.getLong("seq_no");
                        prevHash = rs.getString("entry_hash");
                    }
                }

                long seqNo = prevSeq + 1L;
                Instant now = clockPort.now().truncatedTo(ChronoUnit.MILLIS);
                String safeEvent = eventType == null ? "UNKNOWN" : eventType;
                String safeActor = actorUsername == null ? "SYSTEM" : actorUsername;
                String safeRole = actorRole == null ? "SYSTEM" : actorRole;
                String safeEntity = entityType == null ? "" : entityType;
                String safeId = entityId == null ? "" : entityId;
                String safeDetails = (detailsJson == null || detailsJson.isBlank()) ? "{}" : detailsJson;

                String entryHash = AuditChainCalculator.computeEntryHash(
                        cryptoPort, seqNo, now, safeEvent, safeActor, safeRole, safeEntity, safeId, safeDetails, prevHash);

                String insSql = """
                        INSERT INTO qbank_audit_log(seq_no, event_type, actor_username, actor_role,
                        entity_type, entity_id, details_json, created_at, prev_hash, entry_hash)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
                try (PreparedStatement ps = conn.prepareStatement(insSql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, seqNo);
                    ps.setString(2, safeEvent);
                    ps.setString(3, safeActor);
                    ps.setString(4, safeRole);
                    ps.setString(5, safeEntity);
                    ps.setString(6, safeId);
                    ps.setString(7, safeDetails);
                    ps.setLong(8, now.toEpochMilli());
                    ps.setString(9, prevHash);
                    ps.setString(10, entryHash);
                    long id = JdbcHelper.executeInsertAndReturnId(ps);
                    return new AuditLogEntry(id, seqNo, safeEvent, safeActor, safeRole, safeEntity, safeId, safeDetails, now, prevHash, entryHash);
                }
            }));
        }
    }

    @Override
    public List<AuditLogEntry> findRecent(int limit, String eventType, String actor) {
        return tx.withConnection(conn -> {
            StringBuilder sql = new StringBuilder("SELECT * FROM qbank_audit_log WHERE 1=1");
            List<Object> params = new ArrayList<>();
            if (eventType != null) {
                sql.append(" AND event_type = ?");
                params.add(eventType);
            }
            if (actor != null) {
                sql.append(" AND LOWER(actor_username) = LOWER(?)");
                params.add(actor);
            }
            sql.append(" ORDER BY seq_no DESC LIMIT ?");
            params.add(Math.max(1, limit));

            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    Object p = params.get(i);
                    if (p instanceof Integer iv) {
                        ps.setInt(i + 1, iv);
                    } else {
                        ps.setString(i + 1, String.valueOf(p));
                    }
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<AuditLogEntry> out = new ArrayList<>();
                    while (rs.next()) out.add(mapRow(rs));
                    return out;
                }
            }
        });
    }

    @Override
    public AuditVerificationResult verifyChain() {
        List<AuditLogEntry> allOrdered = tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM qbank_audit_log ORDER BY seq_no ASC");
                 ResultSet rs = ps.executeQuery()) {
                List<AuditLogEntry> out = new ArrayList<>();
                while (rs.next()) out.add(mapRow(rs));
                return out;
            }
        });
        return AuditChainCalculator.verify(cryptoPort, allOrdered);
    }

    private static AuditLogEntry mapRow(ResultSet rs) throws SQLException {
        return new AuditLogEntry(
                rs.getLong("id"),
                rs.getLong("seq_no"),
                rs.getString("event_type"),
                rs.getString("actor_username"),
                rs.getString("actor_role"),
                rs.getString("entity_type"),
                rs.getString("entity_id"),
                rs.getString("details_json"),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                rs.getString("prev_hash"),
                rs.getString("entry_hash")
        );
    }
}
