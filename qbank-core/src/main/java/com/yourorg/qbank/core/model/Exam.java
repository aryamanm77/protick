package com.yourorg.qbank.core.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record Exam(
        Long id,
        Long subjectId,
        String subjectCode,
        String subjectName,
        String title,
        ExamType examType,
        LocalDate examDate,
        int marksPerQuestion,
        int totalMarks,
        int totalQuestions,
        List<Integer> selectedModules,
        Map<Integer, Integer> blueprint,
        int rotationUsed,
        long rngSeed,
        String createdBy,
        Instant createdAt
) {
}
