package com.yourorg.qbank.core.event;

import com.yourorg.qbank.core.model.ExamSessionSlot;

import java.time.Instant;

public record PaperFrozenEvent(
        Long examId,
        Long sessionId,
        String subjectCode,
        ExamSessionSlot slot,
        int chosenSetNumber,
        Instant scheduledStartAt,
        Instant scheduledEndAt,
        String frozenBy,
        Instant occurredAt
) implements QBankEvent {
}
