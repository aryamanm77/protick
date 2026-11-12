package com.yourorg.qbank.persistence.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;

/**
 * Shared null-safe JDBC binding and generated-key helpers compatible with H2, PostgreSQL, SQLite, and MySQL.
 */
final class JdbcHelper {

    private JdbcHelper() {
    }

    static long executeInsertAndReturnId(PreparedStatement ps) throws SQLException {
        int rows = ps.executeUpdate();
        if (rows == 0) {
            throw new SQLException("Insert affected 0 rows");
        }
        try (ResultSet keys = ps.getGeneratedKeys()) {
            if (keys.next()) {
                return keys.getLong(1);
            }
        }
        try (Statement st = ps.getConnection().createStatement();
             ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException ignored) {
        }
        throw new SQLException("Insert succeeded but no generated key was returned");
    }

    static void setNullableInt(PreparedStatement ps, int idx, Integer val) throws SQLException {
        if (val == null) {
            ps.setNull(idx, Types.INTEGER);
        } else {
            ps.setInt(idx, val);
        }
    }

    static void setNullableLong(PreparedStatement ps, int idx, Long val) throws SQLException {
        if (val == null) {
            ps.setNull(idx, Types.BIGINT);
        } else {
            ps.setLong(idx, val);
        }
    }

    static void setNullableInstant(PreparedStatement ps, int idx, Instant val) throws SQLException {
        if (val == null) {
            ps.setNull(idx, Types.BIGINT);
        } else {
            ps.setLong(idx, val.toEpochMilli());
        }
    }

    static Integer getNullableInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    static Instant getInstant(ResultSet rs, String col) throws SQLException {
        long ms = rs.getLong(col);
        return rs.wasNull() ? null : Instant.ofEpochMilli(ms);
    }
}
