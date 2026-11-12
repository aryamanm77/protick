package com.yourorg.qbank.core.event;

import com.yourorg.qbank.core.model.ExamSessionSlot;

import java.time.Instant;

public record PaperGeneratedEvent(
        Long examId,
        Long sessionId,
        String subjectCode,
        ExamSessionSlot slot,
        String generatedBy,
        Instant occurredAt
) implements QBankEvent {
}
