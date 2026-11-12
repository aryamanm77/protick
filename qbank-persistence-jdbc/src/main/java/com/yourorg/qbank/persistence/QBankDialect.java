package com.yourorg.qbank.persistence;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Supported SQL dialects for plain JDBC + isolated Flyway migrations.
 */
public enum QBankDialect {
    H2("h2"),
    POSTGRESQL("postgresql"),
    SQLITE("sqlite"),
    MYSQL("mysql");

    private final String migrationFolder;

    QBankDialect(String migrationFolder) {
        this.migrationFolder = migrationFolder;
    }

    public String migrationFolder() {
        return migrationFolder;
    }

    public static QBankDialect detect(DataSource dataSource, String explicitOverride) {
        if (explicitOverride != null && !explicitOverride.isBlank() && !"auto".equalsIgnoreCase(explicitOverride.trim())) {
            return switch (explicitOverride.trim().toLowerCase(Locale.ROOT)) {
                case "h2" -> H2;
                case "postgresql", "postgres", "pg" -> POSTGRESQL;
                case "sqlite", "sqlite3" -> SQLITE;
                case "mysql", "mariadb" -> MYSQL;
                default -> throw new IllegalArgumentException("Unsupported qbank dialect: " + explicitOverride);
            };
        }
        try (Connection c = dataSource.getConnection()) {
            String name = c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
            if (name.contains("postgres")) {
                return POSTGRESQL;
            }
            if (name.contains("sqlite")) {
                return SQLITE;
            }
            if (name.contains("mysql") || name.contains("mariadb")) {
                return MYSQL;
            }
            return H2;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to inspect database metadata for dialect detection", e);
        }
    }
}
