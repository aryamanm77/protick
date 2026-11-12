package com.yourorg.qbank.persistence.jdbc;

import com.yourorg.qbank.core.exception.ConflictException;
import com.yourorg.qbank.core.model.UserAccount;
import com.yourorg.qbank.core.model.UserRole;
import com.yourorg.qbank.core.port.UserAccountRepository;
import com.yourorg.qbank.persistence.JdbcTransactionManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JdbcUserAccountRepository implements UserAccountRepository {

    private final JdbcTransactionManager tx;

    public JdbcUserAccountRepository(JdbcTransactionManager tx) {
        this.tx = tx;
    }

    @Override
    public UserAccount save(UserAccount u) {
        return tx.withConnection(conn -> {
            if (u.id() == null) {
                String sql = """
                        INSERT INTO qbank_users(username, display_name, role, password_hash, active, must_change_password,
                        failed_login_attempts, locked_until, created_at, updated_at, version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)""";
                try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, u.username());
                    ps.setString(2, u.displayName());
                    ps.setString(3, u.role().name());
                    ps.setString(4, u.passwordHash());
                    ps.setBoolean(5, u.active());
                    ps.setBoolean(6, u.mustChangePassword());
                    ps.setInt(7, u.failedLoginAttempts());
                    JdbcHelper.setNullableInstant(ps, 8, u.lockedUntil());
                    ps.setLong(9, u.createdAt().toEpochMilli());
                    ps.setLong(10, u.updatedAt().toEpochMilli());
                    long id = JdbcHelper.executeInsertAndReturnId(ps);
                    return new UserAccount(id, u.username(), u.displayName(), u.role(), u.passwordHash(),
                            u.active(), u.mustChangePassword(), u.failedLoginAttempts(), u.lockedUntil(),
                            u.createdAt(), u.updatedAt(), 0L);
                }
            }
            String sql = """
                    UPDATE qbank_users SET
                    display_name = ?, role = ?, password_hash = ?, active = ?, must_change_password = ?,
                    failed_login_attempts = ?, locked_until = ?, updated_at = ?, version = version + 1
                    WHERE id = ? AND version = ?""";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, u.displayName());
                ps.setString(2, u.role().name());
                ps.setString(3, u.passwordHash());
                ps.setBoolean(4, u.active());
                ps.setBoolean(5, u.mustChangePassword());
                ps.setInt(6, u.failedLoginAttempts());
                JdbcHelper.setNullableInstant(ps, 7, u.lockedUntil());
                ps.setLong(8, u.updatedAt().toEpochMilli());
                ps.setLong(9, u.id());
                ps.setLong(10, u.version());
                if (ps.executeUpdate() == 0) {
                    throw new ConflictException("Concurrent update conflict for user " + u.username());
                }
                return new UserAccount(u.id(), u.username(), u.displayName(), u.role(), u.passwordHash(),
                        u.active(), u.mustChangePassword(), u.failedLoginAttempts(), u.lockedUntil(),
                        u.createdAt(), u.updatedAt(), u.version() + 1);
            }
        });
    }

    @Override
    public Optional<UserAccount> findById(long id) {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM qbank_users WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public Optional<UserAccount> findByUsernameIgnoreCase(String username) {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM qbank_users WHERE LOWER(username) = LOWER(?)")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<UserAccount> findAll() {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM qbank_users ORDER BY username ASC");
                 ResultSet rs = ps.executeQuery()) {
                List<UserAccount> out = new ArrayList<>();
                while (rs.next()) out.add(mapRow(rs));
                return out;
            }
        });
    }

    @Override
    public boolean existsByRole(UserRole role) {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM qbank_users WHERE role = ?")) {
                ps.setString(1, role.name());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getLong(1) > 0;
                }
            }
        });
    }

    private static UserAccount mapRow(ResultSet rs) throws SQLException {
        return new UserAccount(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("display_name"),
                UserRole.valueOf(rs.getString("role")),
                rs.getString("password_hash"),
                rs.getBoolean("active"),
                rs.getBoolean("must_change_password"),
                rs.getInt("failed_login_attempts"),
                JdbcHelper.getInstant(rs, "locked_until"),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                Instant.ofEpochMilli(rs.getLong("updated_at")),
                rs.getLong("version")
        );
    }
}
