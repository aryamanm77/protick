package com.yourorg.qbank.core.model;

import java.time.Instant;

public record StoredObjectInfo(
        String key,
        long sizeBytes,
        Instant modifiedAt
) {
}
