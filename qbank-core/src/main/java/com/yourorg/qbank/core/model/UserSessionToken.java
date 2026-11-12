package com.yourorg.qbank.core.model;

import java.time.Instant;

public record UserSessionToken(
        String tokenHash,
        Long userId,
        String username,
        UserRole role,
        Instant createdAt,
        Instant expiresAt
) {
}
