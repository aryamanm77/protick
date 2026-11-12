package com.yourorg.qbank.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Runs QBank's isolated Flyway migrations under {@code qbank_schema_history}
 * so embedded usage never collides with host application Flyway histories or tables.
 */
public final class QBankFlywayMigrator {

    private QBankFlywayMigrator() {
    }

    public static MigrateResult migrate(DataSource dataSource, QBankDialect dialect) {
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        Objects.requireNonNull(dialect, "dialect must not be null");

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:qbank/db/migration/" + dialect.migrationFolder())
                .table("qbank_schema_history")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .validateOnMigrate(true)
                .load();
        MigrateResult result = flyway.migrate();
        if (dialect == QBankDialect.H2) {
            // Ensure committed student responses sync immediately rather than waiting in H2's write-delay buffer.
            try (Connection c = dataSource.getConnection();
                 Statement st = c.createStatement()) {
                st.execute("SET WRITE_DELAY 0");
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to configure H2 synchronous write delay", e);
            }
        }
        return result;
    }
}
