package com.yourorg.qbank.core.security;

import com.yourorg.qbank.core.model.AuditLogEntry;
import com.yourorg.qbank.core.model.AuditVerificationResult;
import com.yourorg.qbank.core.port.CryptoPort;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * Computes and verifies tamper-evident hash chains over append-only audit logs.
 */
public final class AuditChainCalculator {

    public static final String GENESIS_PREV_HASH = "0".repeat(64);

    private AuditChainCalculator() {
    }

    public static String computeEntryHash(
            CryptoPort crypto,
            long seqNo,
            Instant createdAt,
            String eventType,
            String actorUsername,
            String actorRole,
            String entityType,
            String entityId,
            String detailsJson,
            String prevHash
    ) {
        Instant canonicalTs = createdAt == null ? null : createdAt.truncatedTo(ChronoUnit.MILLIS);
        String canonical = seqNo
                + "|" + Objects.toString(canonicalTs, "")
                + "|" + Objects.toString(eventType, "")
                + "|" + Objects.toString(actorUsername, "")
                + "|" + Objects.toString(actorRole, "")
                + "|" + Objects.toString(entityType, "")
                + "|" + Objects.toString(entityId, "")
                + "|" + Objects.toString(detailsJson, "{}")
                + "|" + Objects.toString(prevHash, GENESIS_PREV_HASH);
        return crypto.sha256Hex(canonical);
    }

    public static AuditVerificationResult verify(CryptoPort crypto, List<AuditLogEntry> entriesOrderedBySeq) {
        if (entriesOrderedBySeq == null || entriesOrderedBySeq.isEmpty()) {
            return new AuditVerificationResult(true, 0L, null, "Audit log is empty and intact.");
        }

        String expectedPrev = GENESIS_PREV_HASH;
        long expectedSeq = 1L;
        long checked = 0L;

        for (AuditLogEntry entry : entriesOrderedBySeq) {
            if (entry.seqNo() != expectedSeq) {
                return new AuditVerificationResult(
                        false,
                        checked,
                        entry.seqNo(),
                        "Sequence gap or out-of-order entry at seq=" + entry.seqNo() + " (expected " + expectedSeq + ")"
                );
            }
            if (!Objects.equals(expectedPrev, entry.prevHash())) {
                return new AuditVerificationResult(
                        false,
                        checked,
                        entry.seqNo(),
                        "Broken prevHash link at seq=" + entry.seqNo()
                );
            }
            String recomputed = computeEntryHash(
                    crypto,
                    entry.seqNo(),
                    entry.createdAt(),
                    entry.eventType(),
                    entry.actorUsername(),
                    entry.actorRole(),
                    entry.entityType(),
                    entry.entityId(),
                    entry.detailsJson(),
                    entry.prevHash()
            );
            if (!Objects.equals(recomputed, entry.entryHash())) {
                return new AuditVerificationResult(
                        false,
                        checked,
                        entry.seqNo(),
                        "Entry payload tampered at seq=" + entry.seqNo()
                );
            }
            expectedPrev = entry.entryHash();
            expectedSeq++;
            checked++;
        }

        return new AuditVerificationResult(true, checked, null, "All " + checked + " audit entries hash-verified.");
    }
}
