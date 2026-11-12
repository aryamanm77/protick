package com.yourorg.qbank.persistence.jdbc;

import com.yourorg.qbank.core.model.UserRole;
import com.yourorg.qbank.core.model.UserSessionToken;
import com.yourorg.qbank.core.port.UserSessionRepository;
import com.yourorg.qbank.persistence.JdbcTransactionManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

public class JdbcUserSessionRepository implements UserSessionRepository {

    private final JdbcTransactionManager tx;

    public JdbcUserSessionRepository(JdbcTransactionManager tx) {
        this.tx = tx;
    }

    @Override
    public void save(UserSessionToken s) {
        tx.withConnection(conn -> {
            String sql = """
                    INSERT INTO qbank_user_sessions(token_hash, user_id, username, role, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?)""";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, s.tokenHash());
                ps.setLong(2, s.userId());
                ps.setString(3, s.username());
                ps.setString(4, s.role().name());
                ps.setLong(5, s.createdAt().toEpochMilli());
                ps.setLong(6, s.expiresAt().toEpochMilli());
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<UserSessionToken> findByTokenHash(String tokenHash) {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM qbank_user_sessions WHERE token_hash = ?")) {
                ps.setString(1, tokenHash);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public void deleteByTokenHash(String tokenHash) {
        tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM qbank_user_sessions WHERE token_hash = ?")) {
                ps.setString(1, tokenHash);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void deleteByUserId(long userId) {
        tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM qbank_user_sessions WHERE user_id = ?")) {
                ps.setLong(1, userId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void deleteExpired(Instant now) {
        tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM qbank_user_sessions WHERE expires_at <= ?")) {
                ps.setLong(1, now.toEpochMilli());
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static UserSessionToken mapRow(ResultSet rs) throws SQLException {
        return new UserSessionToken(
                rs.getString("token_hash"),
                rs.getLong("user_id"),
                rs.getString("username"),
                UserRole.valueOf(rs.getString("role")),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                Instant.ofEpochMilli(rs.getLong("expires_at"))
        );
    }
}
