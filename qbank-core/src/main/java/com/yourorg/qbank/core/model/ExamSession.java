package com.yourorg.qbank.core.model;

import java.time.Instant;

public record ExamSession(
        Long id,
        Long examId,
        ExamSessionSlot sessionSlot,
        SessionStatus status,
        Integer chosenSetNumber,
        Instant scheduledStartAt,
        Instant scheduledEndAt,
        Instant actualStartedAt,
        Instant actualEndedAt,
        String startedBy,
        String endedBy,
        String frozenBy,
        Instant frozenAt,
        String contentSha256,
        int version
) {
}
