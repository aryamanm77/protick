package com.yourorg.qbank.persistence.jdbc;

import com.yourorg.qbank.core.model.EncryptedMcq;
import com.yourorg.qbank.core.model.EncryptedPayload;
import com.yourorg.qbank.core.port.McqRepository;
import com.yourorg.qbank.persistence.JdbcTransactionManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class JdbcMcqRepository implements McqRepository {

    private final JdbcTransactionManager tx;

    public JdbcMcqRepository(JdbcTransactionManager tx) {
        this.tx = tx;
    }

    @Override
    public void replaceModuleMcqs(long subjectId, int moduleNumber, List<EncryptedMcq> encryptedMcqs) {
        tx.inTransaction(() -> tx.withConnection(conn -> {
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM qbank_mcqs WHERE subject_id = ? AND module_number = ?")) {
                del.setLong(1, subjectId);
                del.setInt(2, moduleNumber);
                del.executeUpdate();
            }

            String insSql = """
                    INSERT INTO qbank_mcqs(id, subject_id, module_number, seq, text_iv, text_cipher,
                    options_iv, options_cipher, correct_iv, correct_cipher, normalized_hash, source_row, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
            try (PreparedStatement ps = conn.prepareStatement(insSql)) {
                for (EncryptedMcq m : encryptedMcqs) {
                    ps.setString(1, m.id());
                    ps.setLong(2, m.subjectId());
                    ps.setInt(3, m.moduleNumber());
                    ps.setInt(4, m.seq());
                    ps.setString(5, m.textEnc().ivBase64());
                    ps.setString(6, m.textEnc().cipherBase64());
                    ps.setString(7, m.optionsEnc().ivBase64());
                    ps.setString(8, m.optionsEnc().cipherBase64());
                    ps.setString(9, m.correctIndexEnc().ivBase64());
                    ps.setString(10, m.correctIndexEnc().cipherBase64());
                    ps.setString(11, m.normalizedHash());
                    ps.setInt(12, m.sourceRow());
                    ps.setLong(13, m.createdAt().toEpochMilli());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        }));
    }

    @Override
    public List<EncryptedMcq> findBySubjectAndModule(long subjectId, int moduleNumber) {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM qbank_mcqs WHERE subject_id = ? AND module_number = ? ORDER BY seq ASC")) {
                ps.setLong(1, subjectId);
                ps.setInt(2, moduleNumber);
                try (ResultSet rs = ps.executeQuery()) {
                    List<EncryptedMcq> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(mapRow(rs));
                    }
                    return out;
                }
            }
        });
    }

    @Override
    public long countAll() {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM qbank_mcqs");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    private static EncryptedMcq mapRow(ResultSet rs) throws SQLException {
        return new EncryptedMcq(
                rs.getString("id"),
                rs.getLong("subject_id"),
                rs.getInt("module_number"),
                rs.getInt("seq"),
                new EncryptedPayload(rs.getString("text_iv"), rs.getString("text_cipher")),
                new EncryptedPayload(rs.getString("options_iv"), rs.getString("options_cipher")),
                new EncryptedPayload(rs.getString("correct_iv"), rs.getString("correct_cipher")),
                rs.getString("normalized_hash"),
                rs.getInt("source_row"),
                Instant.ofEpochMilli(rs.getLong("created_at"))
        );
    }
}
