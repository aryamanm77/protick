package com.yourorg.qbank.persistence.jdbc;

import com.yourorg.qbank.core.model.Subject;
import com.yourorg.qbank.core.port.SubjectRepository;
import com.yourorg.qbank.persistence.JdbcTransactionManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JdbcSubjectRepository implements SubjectRepository {

    private final JdbcTransactionManager tx;

    public JdbcSubjectRepository(JdbcTransactionManager tx) {
        this.tx = tx;
    }

    @Override
    public Subject save(Subject subject) {
        return tx.withConnection(conn -> {
            if (subject.id() == null) {
                String sql = "INSERT INTO qbank_subjects(code, name, expected_questions_per_module, marks_per_question, archived, created_at) VALUES (?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, subject.code());
                    ps.setString(2, subject.name());
                    ps.setInt(3, subject.expectedQuestionsPerModule());
                    ps.setInt(4, subject.marksPerQuestion());
                    ps.setBoolean(5, subject.archived());
                    ps.setLong(6, subject.createdAt().toEpochMilli());
                    long id = JdbcHelper.executeInsertAndReturnId(ps);
                    return new Subject(id, subject.code(), subject.name(),
                            subject.expectedQuestionsPerModule(), subject.marksPerQuestion(),
                            subject.archived(), subject.createdAt());
                }
            }
            String sql = "UPDATE qbank_subjects SET name = ?, expected_questions_per_module = ?, marks_per_question = ?, archived = ? WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, subject.name());
                ps.setInt(2, subject.expectedQuestionsPerModule());
                ps.setInt(3, subject.marksPerQuestion());
                ps.setBoolean(4, subject.archived());
                ps.setLong(5, subject.id());
                ps.executeUpdate();
                return subject;
            }
        });
    }

    @Override
    public Optional<Subject> findById(long id) {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM qbank_subjects WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public Optional<Subject> findByCodeIgnoreCase(String code) {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM qbank_subjects WHERE LOWER(code) = LOWER(?)")) {
                ps.setString(1, code);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<Subject> findAll(boolean includeArchived) {
        return tx.withConnection(conn -> {
            String sql = includeArchived
                    ? "SELECT * FROM qbank_subjects ORDER BY code ASC"
                    : "SELECT * FROM qbank_subjects WHERE archived = FALSE ORDER BY code ASC";
            if (!includeArchived && tx.dialect().name().equals("SQLITE")) {
                sql = "SELECT * FROM qbank_subjects WHERE archived = 0 ORDER BY code ASC";
            }
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                List<Subject> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapRow(rs));
                }
                return out;
            }
        });
    }

    @Override
    public long countAll() {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM qbank_subjects");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    private static Subject mapRow(ResultSet rs) throws SQLException {
        return new Subject(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getInt("expected_questions_per_module"),
                rs.getInt("marks_per_question"),
                rs.getBoolean("archived"),
                Instant.ofEpochMilli(rs.getLong("created_at"))
        );
    }
}
