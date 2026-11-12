package com.yourorg.qbank.core.model;

import java.time.Instant;

/**
 * Encrypted MCQ record stored in database columns (no plaintext question or answer stored at rest).
 */
public record EncryptedMcq(
        String id,
        Long subjectId,
        int moduleNumber,
        int seq,
        EncryptedPayload textEnc,
        EncryptedPayload optionsEnc,
        EncryptedPayload correctIndexEnc,
        String normalizedHash,
        int sourceRow,
        Instant createdAt
) {
}
