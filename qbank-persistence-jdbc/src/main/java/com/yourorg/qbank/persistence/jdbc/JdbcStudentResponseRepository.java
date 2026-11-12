package com.yourorg.qbank.persistence.jdbc;

import com.yourorg.qbank.core.model.StudentResponseRecord;
import com.yourorg.qbank.core.port.StudentResponseRepository;
import com.yourorg.qbank.persistence.JdbcTransactionManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class JdbcStudentResponseRepository implements StudentResponseRepository {

    private final JdbcTransactionManager tx;

    public JdbcStudentResponseRepository(JdbcTransactionManager tx) {
        this.tx = tx;
    }

    @Override
    public StudentResponseRecord upsertResponse(StudentResponseRecord r) {
        return tx.withConnection(conn -> {
            String updSql = """
                    UPDATE qbank_student_responses SET
                    selected_option_index = ?, version_label = ?, client_timestamp = ?, server_received_at = ?
                    WHERE session_id = ? AND roll_number = ? AND question_position = ?""";
            try (PreparedStatement upd = conn.prepareStatement(updSql)) {
                JdbcHelper.setNullableInt(upd, 1, r.selectedOptionIndex());
                upd.setString(2, r.versionLabel());
                JdbcHelper.setNullableInstant(upd, 3, r.clientTimestamp());
                upd.setLong(4, r.serverReceivedAt().toEpochMilli());
                upd.setLong(5, r.sessionId());
                upd.setString(6, r.rollNumber());
                upd.setInt(7, r.questionPosition());
                if (upd.executeUpdate() > 0) {
                    return r;
                }
            }

            String insSql = """
                    INSERT INTO qbank_student_responses(session_id, roll_number, question_position,
                    selected_option_index, version_label, client_timestamp, server_received_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""";
            try (PreparedStatement ins = conn.prepareStatement(insSql, Statement.RETURN_GENERATED_KEYS)) {
                ins.setLong(1, r.sessionId());
                ins.setString(2, r.rollNumber());
                ins.setInt(3, r.questionPosition());
                JdbcHelper.setNullableInt(ins, 4, r.selectedOptionIndex());
                ins.setString(5, r.versionLabel());
                JdbcHelper.setNullableInstant(ins, 6, r.clientTimestamp());
                ins.setLong(7, r.serverReceivedAt().toEpochMilli());
                long id = JdbcHelper.executeInsertAndReturnId(ins);
                return new StudentResponseRecord(
                        id, r.sessionId(), r.rollNumber(), r.questionPosition(),
                        r.selectedOptionIndex(), r.versionLabel(), r.clientTimestamp(), r.serverReceivedAt());
            } catch (SQLException race) {
                try (PreparedStatement updRetry = conn.prepareStatement(updSql)) {
                    JdbcHelper.setNullableInt(updRetry, 1, r.selectedOptionIndex());
                    updRetry.setString(2, r.versionLabel());
                    JdbcHelper.setNullableInstant(updRetry, 3, r.clientTimestamp());
                    updRetry.setLong(4, r.serverReceivedAt().toEpochMilli());
                    updRetry.setLong(5, r.sessionId());
                    updRetry.setString(6, r.rollNumber());
                    updRetry.setInt(7, r.questionPosition());
                    updRetry.executeUpdate();
                    return r;
                }
            }
        });
    }

    @Override
    public List<StudentResponseRecord> findBySessionAndRoll(long sessionId, String rollNumber) {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM qbank_student_responses WHERE session_id = ? AND roll_number = ? ORDER BY question_position ASC")) {
                ps.setLong(1, sessionId);
                ps.setString(2, rollNumber);
                try (ResultSet rs = ps.executeQuery()) {
                    List<StudentResponseRecord> out = new ArrayList<>();
                    while (rs.next()) out.add(mapRow(rs));
                    return out;
                }
            }
        });
    }

    @Override
    public List<StudentResponseRecord> findBySessionId(long sessionId) {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM qbank_student_responses WHERE session_id = ? ORDER BY roll_number, question_position")) {
                ps.setLong(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<StudentResponseRecord> out = new ArrayList<>();
                    while (rs.next()) out.add(mapRow(rs));
                    return out;
                }
            }
        });
    }

    @Override
    public long countAll() {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM qbank_student_responses");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    @Override
    public int deleteResponsesForEndedSessionsBefore(Instant cutoff, String adminActor, String reason) {
        return tx.withConnection(conn -> {
            String sql = """
                    DELETE FROM qbank_student_responses WHERE session_id IN (
                        SELECT id FROM qbank_exam_sessions
                        WHERE status = 'ENDED' AND scheduled_end_at < ? AND (actual_ended_at IS NULL OR actual_ended_at < ?)
                    )""";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, cutoff.toEpochMilli());
                ps.setLong(2, cutoff.toEpochMilli());
                return ps.executeUpdate();
            }
        });
    }

    private static StudentResponseRecord mapRow(ResultSet rs) throws SQLException {
        return new StudentResponseRecord(
                rs.getLong("id"),
                rs.getLong("session_id"),
                rs.getString("roll_number"),
                rs.getInt("question_position"),
                JdbcHelper.getNullableInt(rs, "selected_option_index"),
                rs.getString("version_label"),
                JdbcHelper.getInstant(rs, "client_timestamp"),
                Instant.ofEpochMilli(rs.getLong("server_received_at"))
        );
    }
}
