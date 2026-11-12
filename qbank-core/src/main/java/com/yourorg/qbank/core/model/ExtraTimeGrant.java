package com.yourorg.qbank.core.model;

import java.time.Instant;

public record ExtraTimeGrant(
        Long id,
        Long sessionId,
        String roomCode,
        String rollNumber,
        int extraMinutes,
        String reason,
        String grantedBy,
        Instant grantedAt
) {
}
