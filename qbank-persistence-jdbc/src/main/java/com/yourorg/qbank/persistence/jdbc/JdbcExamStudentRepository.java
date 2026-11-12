package com.yourorg.qbank.persistence.jdbc;

import com.yourorg.qbank.core.exception.ConflictException;
import com.yourorg.qbank.core.model.ExamStudent;
import com.yourorg.qbank.core.model.StudentLoginState;
import com.yourorg.qbank.core.port.ExamStudentRepository;
import com.yourorg.qbank.persistence.JdbcTransactionManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JdbcExamStudentRepository implements ExamStudentRepository {

    private final JdbcTransactionManager tx;

    public JdbcExamStudentRepository(JdbcTransactionManager tx) {
        this.tx = tx;
    }

    @Override
    public ExamStudent save(ExamStudent s) {
        return tx.withConnection(conn -> {
            if (s.id() == null) {
                String sql = """
                        INSERT INTO qbank_exam_students(session_id, roll_number, full_name, room_code, row_number, seat_number,
                        assigned_version, access_code_hash, login_state, active_token_jti, font_scale, high_contrast,
                        accommodation_extra_minutes, granted_extra_minutes, last_question_index, last_heartbeat_at, finished_at, version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
                try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, s.sessionId());
                    ps.setString(2, s.rollNumber());
                    ps.setString(3, s.fullName());
                    ps.setString(4, s.roomCode());
                    JdbcHelper.setNullableInt(ps, 5, s.rowNumber());
                    JdbcHelper.setNullableInt(ps, 6, s.seatNumber());
                    ps.setString(7, s.assignedVersion());
                    ps.setString(8, s.accessCodeHash());
                    ps.setString(9, s.loginState().name());
                    ps.setString(10, s.activeTokenJti());
                    ps.setDouble(11, s.fontScale());
                    ps.setBoolean(12, s.highContrast());
                    ps.setInt(13, s.accommodationExtraMinutes());
                    ps.setInt(14, s.grantedExtraMinutes());
                    ps.setInt(15, s.lastQuestionIndex());
                    JdbcHelper.setNullableInstant(ps, 16, s.lastHeartbeatAt());
                    JdbcHelper.setNullableInstant(ps, 17, s.finishedAt());
                    ps.setInt(18, 0);
                    long id = JdbcHelper.executeInsertAndReturnId(ps);
                    return new ExamStudent(id, s.sessionId(), s.rollNumber(), s.fullName(), s.roomCode(),
                            s.rowNumber(), s.seatNumber(), s.assignedVersion(), s.accessCodeHash(), s.loginState(),
                            s.activeTokenJti(), s.fontScale(), s.highContrast(), s.accommodationExtraMinutes(),
                            s.grantedExtraMinutes(), s.lastQuestionIndex(), s.lastHeartbeatAt(), s.finishedAt(), 0);
                }
            }
            String sql = """
                    UPDATE qbank_exam_students SET
                    access_code_hash = ?, login_state = ?, active_token_jti = ?,
                    font_scale = ?, high_contrast = ?, accommodation_extra_minutes = ?,
                    granted_extra_minutes = ?, last_question_index = ?, last_heartbeat_at = ?,
                    finished_at = ?, version = version + 1
                    WHERE id = ? AND version = ?""";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, s.accessCodeHash());
                ps.setString(2, s.loginState().name());
                ps.setString(3, s.activeTokenJti());
                ps.setDouble(4, s.fontScale());
                ps.setBoolean(5, s.highContrast());
                ps.setInt(6, s.accommodationExtraMinutes());
                ps.setInt(7, s.grantedExtraMinutes());
                ps.setInt(8, s.lastQuestionIndex());
                JdbcHelper.setNullableInstant(ps, 9, s.lastHeartbeatAt());
                JdbcHelper.setNullableInstant(ps, 10, s.finishedAt());
                ps.setLong(11, s.id());
                ps.setInt(12, s.version());
                if (ps.executeUpdate() == 0) {
                    throw new ConflictException("Concurrent update conflict for student " + s.rollNumber());
                }
                return new ExamStudent(s.id(), s.sessionId(), s.rollNumber(), s.fullName(), s.roomCode(),
                        s.rowNumber(), s.seatNumber(), s.assignedVersion(), s.accessCodeHash(), s.loginState(),
                        s.activeTokenJti(), s.fontScale(), s.highContrast(), s.accommodationExtraMinutes(),
                        s.grantedExtraMinutes(), s.lastQuestionIndex(), s.lastHeartbeatAt(), s.finishedAt(), s.version() + 1);
            }
        });
    }

    @Override
    public Optional<ExamStudent> findBySessionAndRoll(long sessionId, String rollNumber) {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM qbank_exam_students WHERE session_id = ? AND UPPER(roll_number) = UPPER(?)")) {
                ps.setLong(1, sessionId);
                ps.setString(2, rollNumber);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<ExamStudent> findBySessionId(long sessionId) {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM qbank_exam_students WHERE session_id = ? ORDER BY roll_number ASC")) {
                ps.setLong(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<ExamStudent> out = new ArrayList<>();
                    while (rs.next()) out.add(mapRow(rs));
                    return out;
                }
            }
        });
    }

    @Override
    public List<ExamStudent> findBySessionAndRoom(long sessionId, String roomCode) {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM qbank_exam_students WHERE session_id = ? AND UPPER(room_code) = UPPER(?) ORDER BY row_number, seat_number, roll_number")) {
                ps.setLong(1, sessionId);
                ps.setString(2, roomCode);
                try (ResultSet rs = ps.executeQuery()) {
                    List<ExamStudent> out = new ArrayList<>();
                    while (rs.next()) out.add(mapRow(rs));
                    return out;
                }
            }
        });
    }

    @Override
    public int maxExtraTimeForSession(long sessionId) {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COALESCE(MAX(accommodation_extra_minutes + granted_extra_minutes), 0) FROM qbank_exam_students WHERE session_id = ?")) {
                ps.setLong(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    @Override
    public long countAll() {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM qbank_exam_students");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    private static ExamStudent mapRow(ResultSet rs) throws SQLException {
        return new ExamStudent(
                rs.getLong("id"),
                rs.getLong("session_id"),
                rs.getString("roll_number"),
                rs.getString("full_name"),
                rs.getString("room_code"),
                JdbcHelper.getNullableInt(rs, "row_number"),
                JdbcHelper.getNullableInt(rs, "seat_number"),
                rs.getString("assigned_version"),
                rs.getString("access_code_hash"),
                StudentLoginState.valueOf(rs.getString("login_state")),
                rs.getString("active_token_jti"),
                rs.getDouble("font_scale"),
                rs.getBoolean("high_contrast"),
                rs.getInt("accommodation_extra_minutes"),
                rs.getInt("granted_extra_minutes"),
                rs.getInt("last_question_index"),
                JdbcHelper.getInstant(rs, "last_heartbeat_at"),
                JdbcHelper.getInstant(rs, "finished_at"),
                rs.getInt("version")
        );
    }
}
