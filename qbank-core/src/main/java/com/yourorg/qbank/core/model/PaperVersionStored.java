package com.yourorg.qbank.core.model;

public record PaperVersionStored(
        Long id,
        Long paperSetId,
        String versionLabel,
        EncryptedPayload questionsEnc,
        EncryptedPayload answerKeyEnc,
        String contentSha256
) {
}
