package com.yourorg.qbank.starter.service;

import com.yourorg.qbank.core.model.ExamSession;
import com.yourorg.qbank.core.model.SessionStatus;
import com.yourorg.qbank.core.model.StoredObjectInfo;
import com.yourorg.qbank.core.port.ClockPort;
import com.yourorg.qbank.core.port.ExamRepository;
import com.yourorg.qbank.core.port.ExamStudentRepository;
import com.yourorg.qbank.core.port.StoragePort;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live runtime telemetry and alert generator surfaced exclusively to ADMIN monitoring.
 */
public class QBankHealthMonitor {

    private final DataSource dataSource;
    private final StoragePort storagePort;
    private final ExamRepository examRepository;
    private final ExamStudentRepository studentRepository;
    private final ClockPort clockPort;
    private final AtomicLong serverErrorsCount = new AtomicLong();
    private volatile Instant lastErrorAt;

    public QBankHealthMonitor(
            DataSource dataSource,
            StoragePort storagePort,
            ExamRepository examRepository,
            ExamStudentRepository studentRepository,
            ClockPort clockPort
    ) {
        this.dataSource = dataSource;
        this.storagePort = storagePort;
        this.examRepository = examRepository;
        this.studentRepository = studentRepository;
        this.clockPort = clockPort;
    }

    public void recordServerError() {
        serverErrorsCount.incrementAndGet();
        lastErrorAt = clockPort.now();
    }

    public HealthSnapshot snapshot() {
        long t0 = System.nanoTime();
        String dbStatus = "UP";
        List<String> alerts = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1")) {
            if (!rs.next()) {
                dbStatus = "DEGRADED";
                alerts.add("Database heartbeat returned empty result");
            }
        } catch (Exception e) {
            dbStatus = "DOWN";
            alerts.add("Database unreachable: " + e.getMessage());
        }
        long latencyMs = Math.max(0L, (System.nanoTime() - t0) / 1_000_000L);

        long freeBytes = storagePort.usableSpaceBytes("backups");
        long freeMb = freeBytes < 0 ? -1 : freeBytes / (1024L * 1024L);
        if (freeMb >= 0 && freeMb < 256) {
            alerts.add("Low disk space on backup volume: " + freeMb + " MB");
        }

        List<ExamSession> sessions = examRepository.findAllSessions();
        long liveCount = sessions.stream().filter(s -> s.status() == SessionStatus.LIVE).count();
        long frozenCount = sessions.stream().filter(s -> s.status() == SessionStatus.FROZEN).count();

        List<StoredObjectInfo> backups = storagePort.list("backups");
        Instant lastBackupAt = backups.isEmpty() ? null : backups.get(0).modifiedAt();
        if (liveCount > 0 && lastBackupAt == null) {
            alerts.add("Live exam session running with no backup on disk");
        }
        if (serverErrorsCount.get() > 0) {
            alerts.add("HTTP 5xx errors observed since boot: " + serverErrorsCount.get());
        }

        return new HealthSnapshot(
                dbStatus,
                latencyMs,
                freeMb,
                liveCount,
                frozenCount,
                studentRepository.countAll(),
                backups.size(),
                lastBackupAt,
                serverErrorsCount.get(),
                lastErrorAt,
                alerts,
                clockPort.now()
        );
    }

    public record HealthSnapshot(
            String dbStatus,
            long dbLatencyMs,
            long diskFreeMb,
            long liveSessionCount,
            long frozenSessionCount,
            long totalRegisteredStudents,
            int backupCount,
            Instant lastBackupAt,
            long serverErrorsCount,
            Instant lastErrorAt,
            List<String> alerts,
            Instant checkedAt
    ) {
    }
}
