package com.yourorg.qbank.core.event;

import com.yourorg.qbank.core.model.ExamSessionSlot;

import java.time.Instant;

public record SessionStoppedEvent(
        Long sessionId,
        ExamSessionSlot slot,
        String stoppedBy,
        Instant occurredAt
) implements QBankEvent {
}
