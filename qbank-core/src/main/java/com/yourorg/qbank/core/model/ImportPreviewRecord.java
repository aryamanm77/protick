package com.yourorg.qbank.core.model;

import java.time.Instant;

public record ImportPreviewRecord(
        String id,
        Long subjectId,
        int moduleNumber,
        String filename,
        String sheetName,
        EncryptedPayload payloadEnc,
        String createdBy,
        Instant createdAt
) {
}
