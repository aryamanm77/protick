package com.yourorg.qbank.standalone;

import com.yourorg.qbank.core.model.AuditVerificationResult;
import com.yourorg.qbank.core.port.AuditPort;
import com.yourorg.qbank.persistence.backup.EncryptedBackupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

@SpringBootApplication
public class QBankStandaloneApplication {

    private static final Logger log = LoggerFactory.getLogger(QBankStandaloneApplication.class);

    public static void main(String[] args) {
        boolean restoreCli = Arrays.stream(args).anyMatch(a -> a.startsWith("--qbank.restore-file="));
        SpringApplication app = new SpringApplication(QBankStandaloneApplication.class);
        if (restoreCli) {
            app.setWebApplicationType(WebApplicationType.NONE);
            app.setDefaultProperties(Map.of("qbank.backup.enabled", "false"));
        }
        ConfigurableApplicationContext ctx = app.run(args);
        if (restoreCli) {
            ctx.close();
        }
    }

    @Bean
    public ApplicationRunner offlineRestoreCliRunner(
            Environment env,
            EncryptedBackupService backupService,
            AuditPort auditPort
    ) {
        return args -> {
            String restoreFile = env.getProperty("qbank.restore-file");
            if (restoreFile == null || restoreFile.isBlank()) {
                return;
            }
            boolean confirmed = Boolean.parseBoolean(env.getProperty("qbank.restore-confirm", "false"));
            if (!confirmed) {
                throw new IllegalStateException("Offline restore requires --qbank.restore-confirm=true");
            }
            Path p = Path.of(restoreFile.trim()).toAbsolutePath().normalize();
            if (!Files.exists(p) || !Files.isRegularFile(p)) {
                throw new IllegalStateException("Backup file not found: " + p);
            }
            byte[] encrypted = Files.readAllBytes(p);
            EncryptedBackupService.BackupInspection res = backupService.restoreFromEncryptedBackup(encrypted, "OFFLINE_CLI");
            AuditVerificationResult auditCheck = auditPort.verifyChain();
            if (!auditCheck.intact()) {
                throw new IllegalStateException("Restored audit chain verification failed: " + auditCheck.message());
            }
            log.info("Offline encrypted restore complete from {} ({} tables restored, audit entries={}, intact={})",
                    p, res.rowCounts().size(), auditCheck.totalChecked(), auditCheck.intact());
        };
    }
}
