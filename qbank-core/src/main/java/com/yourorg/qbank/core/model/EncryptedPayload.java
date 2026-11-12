package com.yourorg.qbank.core.model;

/**
 * AES-GCM encrypted payload persisted at rest in DB columns and encrypted backups.
 */
public record EncryptedPayload(
        String ivBase64,
        String cipherBase64
) {
}
