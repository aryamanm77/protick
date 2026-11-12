package com.yourorg.qbank.core.model;

import java.time.Instant;
import java.util.List;

public record FrozenVersionView(
        Long sessionId,
        String versionLabel,
        Instant scheduledStartAt,
        Instant scheduledEndAt,
        int totalMarks,
        int marksPerQuestion,
        int totalQuestions,
        List<VersionQuestionSnapshot> questions
) {
}
