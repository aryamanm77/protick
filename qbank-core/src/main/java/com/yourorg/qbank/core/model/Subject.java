package com.yourorg.qbank.core.model;

import java.time.Instant;

public record Subject(
        Long id,
        String code,
        String name,
        int expectedQuestionsPerModule,
        int marksPerQuestion,
        boolean archived,
        Instant createdAt
) {
}
