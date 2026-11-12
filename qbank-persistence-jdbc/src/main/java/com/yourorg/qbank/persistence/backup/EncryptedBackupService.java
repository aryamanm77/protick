package com.yourorg.qbank.persistence.backup;

import com.yourorg.qbank.core.exception.BadRequestException;
import com.yourorg.qbank.core.model.StoredObjectInfo;
import com.yourorg.qbank.core.port.AuditPort;
import com.yourorg.qbank.core.port.ClockPort;
import com.yourorg.qbank.core.port.CryptoPort;
import com.yourorg.qbank.core.port.JsonCodecPort;
import com.yourorg.qbank.core.port.StoragePort;
import com.yourorg.qbank.persistence.JdbcTransactionManager;
import com.yourorg.qbank.persistence.QBankDialect;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Dialect-independent AES-256-GCM encrypted backup & atomic restore service for all {@code qbank_*} tables.
 */
public class EncryptedBackupService {

    private static final String BACKUP_AAD = "qbank-backup-v1";
    private static final String BACKUP_NAMESPACE = "backups";
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private static final List<String> TABLES_IN_FK_ORDER = List.of(
            "qbank_subjects",
            "qbank_modules",
            "qbank_mcqs",
            "qbank_import_previews",
            "qbank_exams",
            "qbank_exam_sessions",
            "qbank_paper_sets",
            "qbank_paper_versions",
            "qbank_exam_students",
            "qbank_student_responses",
            "qbank_extra_time_grants",
            "qbank_users",
            "qbank_user_sessions",
            "qbank_audit_log"
    );

    private final JdbcTransactionManager tx;
    private final CryptoPort cryptoPort;
    private final JsonCodecPort jsonCodec;
    private final StoragePort storagePort;
    private final ClockPort clockPort;
    private final AuditPort auditPort;

    public EncryptedBackupService(
            JdbcTransactionManager tx,
            CryptoPort cryptoPort,
            JsonCodecPort jsonCodec,
            StoragePort storagePort,
            ClockPort clockPort,
            AuditPort auditPort
    ) {
        this.tx = Objects.requireNonNull(tx);
        this.cryptoPort = Objects.requireNonNull(cryptoPort);
        this.jsonCodec = Objects.requireNonNull(jsonCodec);
        this.storagePort = Objects.requireNonNull(storagePort);
        this.clockPort = Objects.requireNonNull(clockPort);
        this.auditPort = Objects.requireNonNull(auditPort);
    }

    public BackupArtifactResult createBackup(String actorUsername, String tag, int retentionKeepLast) {
        Instant now = clockPort.now();
        Map<String, List<Map<String, Object>>> tableData = tx.inTransaction(() -> tx.withConnection(conn -> {
            Map<String, List<Map<String, Object>>> dump = new LinkedHashMap<>();
            for (String table : TABLES_IN_FK_ORDER) {
                dump.put(table, dumpTable(conn, table));
            }
            return dump;
        }));

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (var e : tableData.entrySet()) {
            counts.put(e.getKey(), e.getValue().size());
        }

        String dataJson = jsonCodec.toJson(tableData);
        String payloadSha = cryptoPort.sha256Hex(dataJson);

        BackupEnvelope env = new BackupEnvelope(1, now.toString(), tx.dialect().name(), payloadSha, counts, tableData);
        byte[] plainBytes = jsonCodec.toJson(env).getBytes(StandardCharsets.UTF_8);
        byte[] encryptedBytes = cryptoPort.encryptBytes(plainBytes, BACKUP_AAD);

        String safeTag = (tag == null || tag.isBlank()) ? "manual" : tag.replaceAll("[^a-zA-Z0-9_-]", "");
        String filename = "qbank-backup-" + TS_FMT.format(now) + "-" + safeTag + ".qbank.enc";
        storagePort.store(BACKUP_NAMESPACE, filename, encryptedBytes);

        if (retentionKeepLast > 0) {
            pruneOldBackups(retentionKeepLast);
        }

        auditPort.append(
                "BACKUP_CREATED",
                actorUsername == null ? "SYSTEM" : actorUsername,
                "ADMIN",
                "BACKUP",
                filename,
                jsonCodec.toJson(Map.of("bytes", encryptedBytes.length, "payloadSha256", payloadSha))
        );

        return new BackupArtifactResult(filename, encryptedBytes.length, now, payloadSha, counts, encryptedBytes);
    }

    public BackupInspection inspectBackup(byte[] encryptedBytes) {
        BackupEnvelope env = decryptAndVerify(encryptedBytes);
        return new BackupInspection(env.formatVersion(), Instant.parse(env.createdAt()), env.dialect(),
                env.payloadSha256(), env.rowCounts());
    }

    public BackupInspection restoreFromEncryptedBackup(byte[] encryptedBytes, String actorUsername) {
        BackupEnvelope env = decryptAndVerify(encryptedBytes);
        tx.inTransaction(() -> tx.withConnection(conn -> {
            // Delete in reverse FK order
            for (int i = TABLES_IN_FK_ORDER.size() - 1; i >= 0; i--) {
                String tbl = TABLES_IN_FK_ORDER.get(i);
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("DELETE FROM " + tbl);
                }
            }
            // Insert in topological FK order
            for (String tbl : TABLES_IN_FK_ORDER) {
                List<Map<String, Object>> rows = env.tables().getOrDefault(tbl, List.of());
                if (!rows.isEmpty()) {
                    insertRows(conn, tbl, rows);
                    reseedAutoIncrementIfPresent(conn, tx.dialect(), tbl, rows);
                }
            }
            return null;
        }));

        auditPort.append(
                "BACKUP_RESTORED",
                actorUsername == null ? "SYSTEM" : actorUsername,
                "ADMIN",
                "BACKUP",
                env.payloadSha256(),
                jsonCodec.toJson(Map.of("backupCreatedAt", env.createdAt(), "tables", env.rowCounts().size()))
        );

        return new BackupInspection(env.formatVersion(), Instant.parse(env.createdAt()), env.dialect(),
                env.payloadSha256(), env.rowCounts());
    }

    public List<StoredObjectInfo> listBackups() {
        return storagePort.list(BACKUP_NAMESPACE);
    }

    public Optional<byte[]> readBackup(String filename) {
        return storagePort.read(BACKUP_NAMESPACE, filename);
    }

    public void pruneOldBackups(int keepLastN) {
        if (keepLastN <= 0) {
            return;
        }
        List<StoredObjectInfo> backups = storagePort.list(BACKUP_NAMESPACE);
        for (int i = keepLastN; i < backups.size(); i++) {
            storagePort.delete(BACKUP_NAMESPACE, backups.get(i).key());
        }
    }

    private BackupEnvelope decryptAndVerify(byte[] encryptedBytes) {
        if (encryptedBytes == null || encryptedBytes.length == 0) {
            throw new BadRequestException("Backup payload is empty");
        }
        byte[] plainBytes;
        try {
            plainBytes = cryptoPort.decryptBytes(encryptedBytes, BACKUP_AAD);
        } catch (Exception e) {
            throw new BadRequestException("Backup decryption failed (wrong key or corrupt archive): " + e.getMessage());
        }
        BackupEnvelope env = jsonCodec.fromJson(new String(plainBytes, StandardCharsets.UTF_8), BackupEnvelope.class);
        String recomputed = cryptoPort.sha256Hex(jsonCodec.toJson(env.tables()));
        if (!cryptoPort.constantTimeEquals(recomputed, env.payloadSha256())) {
            throw new BadRequestException("Backup integrity SHA-256 verification failed");
        }
        return env;
    }

    private static List<Map<String, Object>> dumpTable(Connection conn, String table) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + table)) {
            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>(cols);
                for (int c = 1; c <= cols; c++) {
                    String colName = meta.getColumnLabel(c).toLowerCase(Locale.ROOT);
                    Object val = rs.getObject(c);
                    if (val instanceof java.sql.Clob) {
                        val = rs.getString(c);
                    }
                    row.put(colName, val);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static void insertRows(Connection conn, String table, List<Map<String, Object>> rows) throws SQLException {
        List<String> cols = new ArrayList<>(rows.get(0).keySet());
        String colList = String.join(", ", cols);
        String placeholders = String.join(", ", Collections.nCopies(cols.size(), "?"));
        String sql = "INSERT INTO " + table + "(" + colList + ") VALUES (" + placeholders + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map<String, Object> row : rows) {
                for (int i = 0; i < cols.size(); i++) {
                    Object v = row.get(cols.get(i));
                    ps.setObject(i + 1, v);
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void reseedAutoIncrementIfPresent(
            Connection conn,
            QBankDialect dialect,
            String table,
            List<Map<String, Object>> rows
    ) throws SQLException {
        if (!rows.get(0).containsKey("id")) {
            return;
        }
        long maxId = 0L;
        for (Map<String, Object> row : rows) {
            Object idObj = row.get("id");
            if (idObj instanceof Number n) {
                maxId = Math.max(maxId, n.longValue());
            }
        }
        if (maxId <= 0L) {
            return;
        }
        long nextId = maxId + 1L;
        try (Statement st = conn.createStatement()) {
            switch (dialect) {
                case H2 -> st.execute("ALTER TABLE " + table + " ALTER COLUMN id RESTART WITH " + nextId);
                case POSTGRESQL -> st.execute("SELECT setval(pg_get_serial_sequence('" + table + "', 'id'), " + maxId + ", true)");
                case MYSQL -> st.execute("ALTER TABLE " + table + " AUTO_INCREMENT = " + nextId);
                case SQLITE -> {
                    try {
                        st.executeUpdate("UPDATE sqlite_sequence SET seq = " + maxId + " WHERE name = '" + table + "'");
                    } catch (SQLException ignored) {
                    }
                }
            }
        } catch (SQLException ignored) {
        }
    }

    public record BackupEnvelope(
            int formatVersion,
            String createdAt,
            String dialect,
            String payloadSha256,
            Map<String, Integer> rowCounts,
            Map<String, List<Map<String, Object>>> tables
    ) {
    }

    public record BackupArtifactResult(
            String filename,
            long sizeBytes,
            Instant createdAt,
            String payloadSha256,
            Map<String, Integer> rowCounts,
            byte[] encryptedBytes
    ) {
    }

    public record BackupInspection(
            int formatVersion,
            Instant createdAt,
            String dialect,
            String payloadSha256,
            Map<String, Integer> rowCounts
    ) {
    }
}
