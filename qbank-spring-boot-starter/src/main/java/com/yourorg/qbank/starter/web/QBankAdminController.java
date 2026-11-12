package com.yourorg.qbank.starter.web;

import com.yourorg.qbank.core.exception.BadRequestException;
import com.yourorg.qbank.core.exception.ConflictException;
import com.yourorg.qbank.core.exception.ForbiddenException;
import com.yourorg.qbank.core.exception.NotFoundException;
import com.yourorg.qbank.core.model.*;
import com.yourorg.qbank.core.port.ClockPort;
import com.yourorg.qbank.core.port.StudentResponseRepository;
import com.yourorg.qbank.core.service.AuthGuard;
import com.yourorg.qbank.core.service.CoreAuditService;
import com.yourorg.qbank.core.service.CoreResultsExportService;
import com.yourorg.qbank.core.service.CoreStandaloneAuthService;
import com.yourorg.qbank.persistence.backup.EncryptedBackupService;
import com.yourorg.qbank.starter.config.QBankProperties;
import com.yourorg.qbank.starter.service.QBankHealthMonitor;
import com.yourorg.qbank.core.port.AuthPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("${qbank.base-path:/qbank}/api/v1/admin")
public class QBankAdminController {

    private final QBankProperties props;
    private final AuthGuard authGuard;
    private final CoreStandaloneAuthService standaloneAuthService;
    private final boolean hostModeActive;
    private final CoreAuditService auditService;
    private final CoreResultsExportService resultsExportService;
    private final EncryptedBackupService backupService;
    private final QBankHealthMonitor healthMonitor;
    private final StudentResponseRepository responseRepository;
    private final ClockPort clockPort;

    public QBankAdminController(
            QBankProperties props,
            AuthPort authPort,
            CoreStandaloneAuthService standaloneAuthService,
            boolean hostModeActive,
            CoreAuditService auditService,
            CoreResultsExportService resultsExportService,
            EncryptedBackupService backupService,
            QBankHealthMonitor healthMonitor,
            StudentResponseRepository responseRepository,
            ClockPort clockPort
    ) {
        this.props = props;
        this.authGuard = new AuthGuard(authPort);
        this.standaloneAuthService = standaloneAuthService;
        this.hostModeActive = hostModeActive;
        this.auditService = auditService;
        this.resultsExportService = resultsExportService;
        this.backupService = backupService;
        this.healthMonitor = healthMonitor;
        this.responseRepository = responseRepository;
        this.clockPort = clockPort;
    }

    @GetMapping("/users")
    public List<CoreStandaloneAuthService.UserView> listUsers() {
        requireStandalone();
        return standaloneAuthService.listUsers();
    }

    @PostMapping("/users")
    public CoreStandaloneAuthService.UserView createUser(@RequestBody CreateUserRequest req) {
        requireStandalone();
        return standaloneAuthService.createUser(req.username(), req.displayName(), req.role(), req.temporaryPassword());
    }

    @PutMapping("/users/{userId}")
    public CoreStandaloneAuthService.UserView updateUser(@PathVariable long userId, @RequestBody UpdateUserRequest req) {
        requireStandalone();
        return standaloneAuthService.updateUserStatus(userId, req.active(), req.clearLockout());
    }

    @GetMapping("/health")
    public QBankHealthMonitor.HealthSnapshot health() {
        authGuard.requireRole(UserRole.ADMIN);
        return healthMonitor.snapshot();
    }

    @GetMapping("/audit")
    public List<AuditLogEntry> listAudit(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String actor
    ) {
        return auditService.findRecent(limit, eventType, actor);
    }

    @PostMapping("/audit/verify")
    public AuditVerificationResult verifyAuditChain() {
        return auditService.verifyChain();
    }

    @GetMapping("/backups")
    public List<StoredObjectInfo> listBackups() {
        authGuard.requireRole(UserRole.ADMIN);
        return backupService.listBackups();
    }

    @PostMapping("/backups")
    public BackupCreatedSummary createBackup(@RequestParam(defaultValue = "manual") String tag) {
        var actor = authGuard.requireRole(UserRole.ADMIN);
        var res = backupService.createBackup(actor.username(), tag, props.getBackup().getKeepLast());
        return new BackupCreatedSummary(res.filename(), res.sizeBytes(), res.createdAt(), res.payloadSha256(), res.rowCounts());
    }

    @GetMapping("/backups/{filename:.+}")
    public ResponseEntity<byte[]> downloadBackup(@PathVariable String filename) {
        authGuard.requireRole(UserRole.ADMIN);
        byte[] content = backupService.readBackup(filename)
                .orElseThrow(() -> new NotFoundException("Backup file not found: " + filename));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(content);
    }

    @PostMapping(value = "/backups/inspect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EncryptedBackupService.BackupInspection inspectBackup(@RequestParam("file") MultipartFile file) throws IOException {
        authGuard.requireRole(UserRole.ADMIN);
        if (file == null || file.isEmpty()) throw new BadRequestException("Backup file is required");
        return backupService.inspectBackup(file.getBytes());
    }

    @PostMapping(value = "/backups/restore", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EncryptedBackupService.BackupInspection restoreBackup(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean confirm
    ) throws IOException {
        var actor = authGuard.requireRole(UserRole.ADMIN);
        if (!confirm) {
            throw new BadRequestException("Restore requires confirm=true");
        }
        if (file == null || file.isEmpty()) throw new BadRequestException("Backup file is required");
        return backupService.restoreFromEncryptedBackup(file.getBytes(), actor.username());
    }

    @GetMapping("/sessions/{sessionId}/results")
    public ResultsJsonResponse getResults(@PathVariable long sessionId) {
        var exp = resultsExportService.exportSessionResults(sessionId);
        return new ResultsJsonResponse(
                exp.exam().id(),
                exp.exam().subjectCode(),
                exp.exam().title(),
                exp.session().id(),
                exp.session().sessionSlot(),
                exp.csvSha256(),
                exp.xlsxSha256(),
                exp.results()
        );
    }

    @GetMapping("/sessions/{sessionId}/results.csv")
    public ResponseEntity<byte[]> downloadCsv(@PathVariable long sessionId) {
        var exp = resultsExportService.exportSessionResults(sessionId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"session-" + sessionId + "-results.csv\"")
                .header("X-Content-SHA256", exp.csvSha256())
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(exp.csvBytes());
    }

    @GetMapping("/sessions/{sessionId}/results.xlsx")
    public ResponseEntity<byte[]> downloadXlsx(@PathVariable long sessionId) {
        var exp = resultsExportService.exportSessionResults(sessionId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"session-" + sessionId + "-results.xlsx\"")
                .header("X-Content-SHA256", exp.xlsxSha256())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(exp.xlsxBytes());
    }

    @DeleteMapping("/retention/purge")
    public Map<String, Object> purgeExpiredResponses(@RequestParam(required = false) Integer olderThanDays) {
        var actor = authGuard.requireRole(UserRole.ADMIN);
        int days = olderThanDays != null ? Math.max(1, olderThanDays) : Math.max(1, props.getRetentionDays());
        Instant cutoff = clockPort.now().minus(days, ChronoUnit.DAYS);
        int deleted = responseRepository.deleteResponsesForEndedSessionsBefore(cutoff, actor.username(), "Retention purge");
        auditService.recordSystemEvent(
                "RETENTION_PURGE", actor.username(), UserRole.ADMIN.name(),
                "STUDENT_RESPONSES", "BATCH", Map.of("olderThanDays", days, "deletedRows", deleted));
        return Map.of("deletedResponses", deleted, "cutoff", cutoff.toString());
    }

    private void requireStandalone() {
        if (hostModeActive || standaloneAuthService == null) {
            throw new ForbiddenException("Staff user management is delegated to host application");
        }
    }

    public record CreateUserRequest(String username, String displayName, UserRole role, String temporaryPassword) {}
    public record UpdateUserRequest(boolean active, boolean clearLockout) {}
    public record BackupCreatedSummary(
            String filename, long sizeBytes, Instant createdAt, String payloadSha256, Map<String, Integer> rowCounts) {}
    public record ResultsJsonResponse(
            long examId, String subjectCode, String title, long sessionId, ExamSessionSlot slot,
            String csvSha256, String xlsxSha256, List<StudentScoreResult> results) {}
}
