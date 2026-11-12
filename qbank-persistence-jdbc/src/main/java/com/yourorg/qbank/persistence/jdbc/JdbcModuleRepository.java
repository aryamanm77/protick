package com.yourorg.qbank.persistence.jdbc;

import com.yourorg.qbank.core.model.ModuleStatus;
import com.yourorg.qbank.core.model.SubjectModule;
import com.yourorg.qbank.core.port.ModuleRepository;
import com.yourorg.qbank.persistence.JdbcTransactionManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JdbcModuleRepository implements ModuleRepository {

    private final JdbcTransactionManager tx;

    public JdbcModuleRepository(JdbcTransactionManager tx) {
        this.tx = tx;
    }

    @Override
    public SubjectModule save(SubjectModule m) {
        return tx.withConnection(conn -> {
            if (m.id() == null) {
                String sql = """
                        INSERT INTO qbank_modules(subject_id, subject_code, module_number, status, question_count,
                        last_import_filename, last_imported_by, last_imported_at, last_error_count, last_warning_count)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
                try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, m.subjectId());
                    ps.setString(2, m.subjectCode());
                    ps.setInt(3, m.moduleNumber());
                    ps.setString(4, m.status().name());
                    ps.setInt(5, m.questionCount());
                    ps.setString(6, m.lastImportFilename());
                    ps.setString(7, m.lastImportedBy());
                    JdbcHelper.setNullableInstant(ps, 8, m.lastImportedAt());
                    ps.setInt(9, m.lastErrorCount());
                    ps.setInt(10, m.lastWarningCount());
                    long id = JdbcHelper.executeInsertAndReturnId(ps);
                    return new SubjectModule(id, m.subjectId(), m.subjectCode(), m.moduleNumber(),
                            m.status(), m.questionCount(), m.lastImportFilename(), m.lastImportedBy(),
                            m.lastImportedAt(), m.lastErrorCount(), m.lastWarningCount());
                }
            }
            String sql = """
                    UPDATE qbank_modules SET status = ?, question_count = ?, last_import_filename = ?,
                    last_imported_by = ?, last_imported_at = ?, last_error_count = ?, last_warning_count = ?
                    WHERE id = ?""";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, m.status().name());
                ps.setInt(2, m.questionCount());
                ps.setString(3, m.lastImportFilename());
                ps.setString(4, m.lastImportedBy());
                JdbcHelper.setNullableInstant(ps, 5, m.lastImportedAt());
                ps.setInt(6, m.lastErrorCount());
                ps.setInt(7, m.lastWarningCount());
                ps.setLong(8, m.id());
                ps.executeUpdate();
                return m;
            }
        });
    }

    @Override
    public Optional<SubjectModule> findBySubjectAndNumber(long subjectId, int moduleNumber) {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM qbank_modules WHERE subject_id = ? AND module_number = ?")) {
                ps.setLong(1, subjectId);
                ps.setInt(2, moduleNumber);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<SubjectModule> findBySubjectId(long subjectId) {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM qbank_modules WHERE subject_id = ? ORDER BY module_number ASC")) {
                ps.setLong(1, subjectId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<SubjectModule> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(mapRow(rs));
                    }
                    return out;
                }
            }
        });
    }

    private static SubjectModule mapRow(ResultSet rs) throws SQLException {
        return new SubjectModule(
                rs.getLong("id"),
                rs.getLong("subject_id"),
                rs.getString("subject_code"),
                rs.getInt("module_number"),
                ModuleStatus.valueOf(rs.getString("status")),
                rs.getInt("question_count"),
                rs.getString("last_import_filename"),
                rs.getString("last_imported_by"),
                JdbcHelper.getInstant(rs, "last_imported_at"),
                rs.getInt("last_error_count"),
                rs.getInt("last_warning_count")
        );
    }
}
