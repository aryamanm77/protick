package com.yourorg.qbank.core.model;

import java.time.Instant;
import java.time.LocalDate;

public record FrozenPaperMeta(
        Long sessionId,
        Long examId,
        String subjectCode,
        String subjectName,
        String title,
        ExamType examType,
        ExamSessionSlot slot,
        LocalDate examDate,
        SessionStatus status,
        int chosenSetNumber,
        int totalMarks,
        int marksPerQuestion,
        int totalQuestions,
        Instant scheduledStartAt,
        Instant scheduledEndAt,
        Instant actualStartedAt,
        Instant actualEndedAt,
        String frozenBy,
        Instant frozenAt,
        String contentSha256
) {
}
