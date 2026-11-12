package com.yourorg.qbank.core.event;

import com.yourorg.qbank.core.model.ExamSessionSlot;

import java.time.Instant;

public record SessionStartedEvent(
        Long sessionId,
        ExamSessionSlot slot,
        String startedBy,
        Instant occurredAt
) implements QBankEvent {
}
