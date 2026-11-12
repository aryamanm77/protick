package com.yourorg.qbank.core.model;

import java.time.Instant;

public record StudentResponseRecord(
        Long id,
        Long sessionId,
        String rollNumber,
        int questionPosition,
        Integer selectedOptionIndex,
        String versionLabel,
        Instant clientTimestamp,
        Instant serverReceivedAt
) {
}
