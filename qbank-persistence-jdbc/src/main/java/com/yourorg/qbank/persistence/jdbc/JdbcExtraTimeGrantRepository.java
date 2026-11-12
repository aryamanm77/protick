package com.yourorg.qbank.persistence.jdbc;

import com.yourorg.qbank.core.model.ExtraTimeGrant;
import com.yourorg.qbank.core.port.ExtraTimeGrantRepository;
import com.yourorg.qbank.persistence.JdbcTransactionManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class JdbcExtraTimeGrantRepository implements ExtraTimeGrantRepository {

    private final JdbcTransactionManager tx;

    public JdbcExtraTimeGrantRepository(JdbcTransactionManager tx) {
        this.tx = tx;
    }

    @Override
    public ExtraTimeGrant save(ExtraTimeGrant g) {
        return tx.withConnection(conn -> {
            String sql = """
                    INSERT INTO qbank_extra_time_grants(session_id, room_code, roll_number, extra_minutes, reason, granted_by, granted_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, g.sessionId());
                ps.setString(2, g.roomCode());
                ps.setString(3, g.rollNumber());
                ps.setInt(4, g.extraMinutes());
                ps.setString(5, g.reason());
                ps.setString(6, g.grantedBy());
                ps.setLong(7, g.grantedAt().toEpochMilli());
                long id = JdbcHelper.executeInsertAndReturnId(ps);
                return new ExtraTimeGrant(id, g.sessionId(), g.roomCode(), g.rollNumber(),
                        g.extraMinutes(), g.reason(), g.grantedBy(), g.grantedAt());
            }
        });
    }

    @Override
    public List<ExtraTimeGrant> findBySessionId(long sessionId) {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM qbank_extra_time_grants WHERE session_id = ? ORDER BY id DESC")) {
                ps.setLong(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<ExtraTimeGrant> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new ExtraTimeGrant(
                                rs.getLong("id"),
                                rs.getLong("session_id"),
                                rs.getString("room_code"),
                                rs.getString("roll_number"),
                                rs.getInt("extra_minutes"),
                                rs.getString("reason"),
                                rs.getString("granted_by"),
                                Instant.ofEpochMilli(rs.getLong("granted_at"))
                        ));
                    }
                    return out;
                }
            }
        });
    }
}
