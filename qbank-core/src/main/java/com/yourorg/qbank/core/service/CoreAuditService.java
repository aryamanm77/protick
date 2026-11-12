package com.yourorg.qbank.core.service;

import com.yourorg.qbank.core.model.AuditLogEntry;
import com.yourorg.qbank.core.model.AuditVerificationResult;
import com.yourorg.qbank.core.model.UserRole;
import com.yourorg.qbank.core.port.AuditPort;
import com.yourorg.qbank.core.port.AuthPort;
import com.yourorg.qbank.core.port.JsonCodecPort;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Core service mediating tamper-evident audit logging and ADMIN verification queries.
 */
public class CoreAuditService {

    private final AuditPort auditPort;
    private final JsonCodecPort jsonCodec;
    private final AuthGuard authGuard;

    public CoreAuditService(AuditPort auditPort, JsonCodecPort jsonCodec, AuthPort authPort) {
        this.auditPort = Objects.requireNonNull(auditPort, "auditPort must not be null");
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec must not be null");
        this.authGuard = new AuthGuard(authPort);
    }

    public List<AuditLogEntry> findRecent(int limit, String eventType, String actor) {
        authGuard.requireRole(UserRole.ADMIN);
        int bounded = Math.max(1, Math.min(limit <= 0 ? 100 : limit, 1000));
        return auditPort.findRecent(bounded, blankToNull(eventType), blankToNull(actor));
    }

    public AuditVerificationResult verifyChain() {
        AuthPort.AuthActor actor = authGuard.requireRole(UserRole.ADMIN);
        AuditVerificationResult result = auditPort.verifyChain();
        auditPort.append(
                "AUDIT_CHAIN_VERIFIED",
                actor.username(),
                AuthGuard.primaryRoleName(actor),
                "AUDIT_LOG",
                "CHAIN",
                jsonCodec.toJson(Map.of(
                        "intact", result.intact(),
                        "totalChecked", result.totalChecked()
                ))
        );
        return result;
    }

    public void recordSystemEvent(
            String eventType,
            String actorUsername,
            String actorRole,
            String entityType,
            String entityId,
            Map<String, Object> details
    ) {
        String detailsJson = details == null || details.isEmpty() ? "{}" : jsonCodec.toJson(details);
        auditPort.append(eventType, actorUsername, actorRole, entityType, entityId, detailsJson);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
