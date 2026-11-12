package com.yourorg.qbank.starter.service;

import com.yourorg.qbank.core.model.ExamSession;
import com.yourorg.qbank.core.model.SessionStatus;
import com.yourorg.qbank.core.port.ClockPort;
import com.yourorg.qbank.core.port.ExamRepository;
import com.yourorg.qbank.core.port.ImportPreviewRepository;
import com.yourorg.qbank.persistence.backup.EncryptedBackupService;
import com.yourorg.qbank.starter.config.QBankProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Automatic encrypted database backups (daily schedule + periodic while sessions are LIVE)
 * and cleanup of expired draft import previews.
 */
public class QBankBackupScheduler {

    private static final Logger log = LoggerFactory.getLogger(QBankBackupScheduler.class);

    private final QBankProperties props;
    private final EncryptedBackupService backupService;
    private final ExamRepository examRepository;
    private final ImportPreviewRepository previewRepository;
    private final ClockPort clockPort;

    public QBankBackupScheduler(
            QBankProperties props,
            EncryptedBackupService backupService,
            ExamRepository examRepository,
            ImportPreviewRepository previewRepository,
            ClockPort clockPort
    ) {
        this.props = props;
        this.backupService = backupService;
        this.examRepository = examRepository;
        this.previewRepository = previewRepository;
        this.clockPort = clockPort;
    }

    @Scheduled(cron = "${qbank.backup.daily-cron:0 0 2 * * *}")
    public void runDailyBackup() {
        if (!props.getBackup().isEnabled()) {
            return;
        }
        try {
            backupService.createBackup("SCHEDULER", "daily", props.getBackup().getKeepLast());
            Instant cutoff = clockPort.now().minus(24, ChronoUnit.HOURS);
            previewRepository.deleteCreatedBefore(cutoff);
        } catch (Exception e) {
            log.error("Scheduled daily encrypted backup failed", e);
        }
    }

    @Scheduled(fixedDelayString = "#{${qbank.backup.live-session-interval-minutes:5} * 60000}")
    public void runLiveExamSnapshotBackup() {
        if (!props.getBackup().isEnabled()) {
            return;
        }
        boolean anyLive = examRepository.findAllSessions().stream()
                .anyMatch(s -> s.status() == SessionStatus.LIVE);
        if (!anyLive) {
            return;
        }
        try {
            backupService.createBackup("SCHEDULER", "live", props.getBackup().getKeepLast());
        } catch (Exception e) {
            log.error("Live exam snapshot backup failed", e);
        }
    }
}
