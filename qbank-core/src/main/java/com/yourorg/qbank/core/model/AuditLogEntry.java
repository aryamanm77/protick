package com.yourorg.qbank.core.model;

import java.time.Instant;

public record AuditLogEntry(
        Long id,
        long seqNo,
        String eventType,
        String actorUsername,
        String actorRole,
        String entityType,
        String entityId,
        String detailsJson,
        Instant createdAt,
        String prevHash,
        String entryHash
) {
}
