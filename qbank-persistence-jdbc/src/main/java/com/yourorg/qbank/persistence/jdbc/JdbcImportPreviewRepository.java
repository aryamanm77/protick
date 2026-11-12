package com.yourorg.qbank.persistence.jdbc;

import com.yourorg.qbank.core.model.EncryptedPayload;
import com.yourorg.qbank.core.model.ImportPreviewRecord;
import com.yourorg.qbank.core.port.ImportPreviewRepository;
import com.yourorg.qbank.persistence.JdbcTransactionManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

public class JdbcImportPreviewRepository implements ImportPreviewRepository {

    private final JdbcTransactionManager tx;

    public JdbcImportPreviewRepository(JdbcTransactionManager tx) {
        this.tx = tx;
    }

    @Override
    public ImportPreviewRecord save(ImportPreviewRecord record) {
        return tx.withConnection(conn -> {
            try (PreparedStatement del = conn.prepareStatement("DELETE FROM qbank_import_previews WHERE id = ?")) {
                del.setString(1, record.id());
                del.executeUpdate();
            }
            String sql = """
                    INSERT INTO qbank_import_previews(id, subject_id, module_number, filename, sheet_name,
                    payload_iv, payload_cipher, created_by, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, record.id());
                ps.setLong(2, record.subjectId());
                ps.setInt(3, record.moduleNumber());
                ps.setString(4, record.filename());
                ps.setString(5, record.sheetName());
                ps.setString(6, record.payloadEnc().ivBase64());
                ps.setString(7, record.payloadEnc().cipherBase64());
                ps.setString(8, record.createdBy());
                ps.setLong(9, record.createdAt().toEpochMilli());
                ps.executeUpdate();
            }
            return record;
        });
    }

    @Override
    public Optional<ImportPreviewRecord> findById(String id) {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM qbank_import_previews WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public void deleteById(String id) {
        tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM qbank_import_previews WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void deleteCreatedBefore(Instant cutoff) {
        tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM qbank_import_previews WHERE created_at < ?")) {
                ps.setLong(1, cutoff.toEpochMilli());
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static ImportPreviewRecord mapRow(ResultSet rs) throws SQLException {
        return new ImportPreviewRecord(
                rs.getString("id"),
                rs.getLong("subject_id"),
                rs.getInt("module_number"),
                rs.getString("filename"),
                rs.getString("sheet_name"),
                new EncryptedPayload(rs.getString("payload_iv"), rs.getString("payload_cipher")),
                rs.getString("created_by"),
                Instant.ofEpochMilli(rs.getLong("created_at"))
        );
    }
}
