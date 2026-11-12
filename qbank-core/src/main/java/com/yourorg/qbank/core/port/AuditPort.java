package com.yourorg.qbank.core.port;

import com.yourorg.qbank.core.model.AuditLogEntry;
import com.yourorg.qbank.core.model.AuditVerificationResult;

import java.util.List;

/**
 * Append-only hash-chained audit trail port.
 */
public interface AuditPort {

    AuditLogEntry append(
            String eventType,
            String actorUsername,
            String actorRole,
            String entityType,
            String entityId,
            String detailsJson
    );

    List<AuditLogEntry> findRecent(int limit, String eventType, String actor);

    AuditVerificationResult verifyChain();
}
