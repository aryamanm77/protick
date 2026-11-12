package com.yourorg.qbank.persistence;

import com.yourorg.qbank.core.port.TransactionPort;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Lightweight ThreadLocal-bound JDBC transaction manager requiring zero external transaction frameworks.
 */
public class JdbcTransactionManager implements TransactionPort {

    private final DataSource dataSource;
    private final QBankDialect dialect;
    private final ThreadLocal<Connection> activeConnection = new ThreadLocal<>();

    public JdbcTransactionManager(DataSource dataSource, QBankDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.dialect = Objects.requireNonNull(dialect, "dialect must not be null");
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        Connection existing = activeConnection.get();
        if (existing != null) {
            return work.get();
        }
        try (Connection conn = openPreparedConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            activeConnection.set(conn);
            try {
                T result = work.get();
                conn.commit();
                return result;
            } catch (RuntimeException | Error ex) {
                rollbackQuietly(conn);
                throw ex;
            } finally {
                activeConnection.remove();
                restoreAutoCommitQuietly(conn, originalAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("JDBC transaction failure", e);
        }
    }

    public <T> T withConnection(SqlCallback<T> callback) {
        Connection txConn = activeConnection.get();
        if (txConn != null) {
            try {
                return callback.execute(txConn);
            } catch (SQLException e) {
                throw new IllegalStateException("JDBC query failure inside transaction", e);
            }
        }
        try (Connection conn = openPreparedConnection()) {
            return callback.execute(conn);
        } catch (SQLException e) {
            throw new IllegalStateException("JDBC query failure", e);
        }
    }

    public QBankDialect dialect() {
        return dialect;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    private Connection openPreparedConnection() throws SQLException {
        Connection c = dataSource.getConnection();
        if (dialect == QBankDialect.SQLITE) {
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA busy_timeout = 5000");
                st.execute("PRAGMA foreign_keys = ON");
            }
        }
        return c;
    }

    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
        }
    }

    private static void restoreAutoCommitQuietly(Connection conn, boolean originalAutoCommit) {
        try {
            conn.setAutoCommit(originalAutoCommit);
        } catch (SQLException ignored) {
        }
    }

    @FunctionalInterface
    public interface SqlCallback<T> {
        T execute(Connection connection) throws SQLException;
    }
}
