package com.yourorg.qbank.core.model;

public record AuditVerificationResult(
        boolean intact,
        long totalChecked,
        Long firstBrokenSeqNo,
        String message
) {
}
