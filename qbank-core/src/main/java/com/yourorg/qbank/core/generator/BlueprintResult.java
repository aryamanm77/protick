package com.yourorg.qbank.core.generator;

import com.yourorg.qbank.core.model.ExamType;

import java.util.List;
import java.util.Map;

public record BlueprintResult(
        ExamType examType,
        int totalMarks,
        int marksPerQuestion,
        int totalQuestions,
        List<Integer> selectedModules,
        int rotationOffset,
        Map<Integer, Integer> moduleCounts,
        String summaryText
) {
}
