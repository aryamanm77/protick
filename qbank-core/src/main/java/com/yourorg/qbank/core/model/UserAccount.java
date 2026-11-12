package com.yourorg.qbank.core.model;

import java.time.Instant;

public record UserAccount(
        Long id,
        String username,
        String displayName,
        UserRole role,
        String passwordHash,
        boolean active,
        boolean mustChangePassword,
        int failedLoginAttempts,
        Instant lockedUntil,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
}
