package com.yourorg.qbank.core.generator;

import com.yourorg.qbank.core.model.ExamType;

import java.util.*;
import java.util.stream.Collectors;

public class BlueprintCalculator {

    private static final List<Integer> ALL_MODULES = List.of(1, 2, 3, 4, 5);

    public BlueprintResult calculate(
            ExamType examType,
            List<Integer> requestedModules,
            int marksPerQuestion,
            int rotationOffset
    ) {
        if (examType == null) {
            throw new IllegalArgumentException("Exam type is required");
        }
        if (marksPerQuestion <= 0) {
            throw new IllegalArgumentException("marksPerQuestion must be positive");
        }

        int totalMarks = examType.getDefaultTotalMarks();
        if (totalMarks % marksPerQuestion != 0) {
            throw new IllegalArgumentException(
                    "Total marks (" + totalMarks + ") must be divisible by marksPerQuestion (" + marksPerQuestion + ")");
        }
        int totalQuestions = totalMarks / marksPerQuestion;

        List<Integer> effectiveModules = resolveModules(examType, requestedModules);
        int k = effectiveModules.size();
        int base = totalQuestions / k;
        int remainder = totalQuestions % k;
        int safeRotation = Math.floorMod(rotationOffset, k);

        Map<Integer, Integer> rawByModule = new LinkedHashMap<>();
        for (int mod : effectiveModules) {
            rawByModule.put(mod, base);
        }
        for (int i = 0; i < remainder; i++) {
            int idx = (safeRotation + i) % k;
            int mod = effectiveModules.get(idx);
            rawByModule.put(mod, rawByModule.get(mod) + 1);
        }

        String breakdown = rawByModule.entrySet().stream()
                .map(e -> "M" + e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining(" + "));
        String summaryText = totalQuestions + " questions (" + totalMarks + " marks): " + breakdown;

        return new BlueprintResult(
                examType,
                totalMarks,
                marksPerQuestion,
                totalQuestions,
                effectiveModules,
                safeRotation,
                Collections.unmodifiableMap(rawByModule),
                summaryText
        );
    }

    private List<Integer> resolveModules(ExamType examType, List<Integer> requestedModules) {
        if (examType == ExamType.SEE) {
            return ALL_MODULES;
        }
        if (requestedModules == null || requestedModules.isEmpty()) {
            throw new IllegalArgumentException("At least one module (1-5) must be selected for INTERNAL exams");
        }
        List<Integer> sorted = requestedModules.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("At least one module (1-5) must be selected for INTERNAL exams");
        }
        for (int mod : sorted) {
            if (mod < 1 || mod > 5) {
                throw new IllegalArgumentException("Module number must be between 1 and 5, got: " + mod);
            }
        }
        return sorted;
    }
}
