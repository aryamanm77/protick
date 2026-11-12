package com.yourorg.qbank.core.generator;

import com.yourorg.qbank.core.model.ExamType;
import com.yourorg.qbank.core.model.ModuleUnit;

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
        int safeRotation = Math.floorMod(rotationOffset, k);

        // Weighted proportional allocation (Full = weight 2, Half = weight 1) using Hamilton largest remainder
        // with deterministic rotating tie-break so both whole (e.g. 10+10+10) and half (e.g. 1.5 -> 20+10) split cleanly.
        int[] weights = new int[k];
        long totalWeight = 0;
        for (int i = 0; i < k; i++) {
            weights[i] = ModuleUnit.weight(effectiveModules.get(i));
            totalWeight += weights[i];
        }

        int[] counts = new int[k];
        long[] remNumerators = new long[k];
        int assigned = 0;
        for (int i = 0; i < k; i++) {
            long scaled = (long) totalQuestions * weights[i];
            counts[i] = (int) (scaled / totalWeight);
            remNumerators[i] = scaled % totalWeight;
            assigned += counts[i];
        }

        int remainder = totalQuestions - assigned;
        List<Integer> priorityOrder = new ArrayList<>(k);
        for (int i = 0; i < k; i++) {
            priorityOrder.add(i);
        }
        priorityOrder.sort((a, b) -> {
            int byRem = Long.compare(remNumerators[b], remNumerators[a]);
            if (byRem != 0) {
                return byRem;
            }
            int distA = Math.floorMod(a - safeRotation, k);
            int distB = Math.floorMod(b - safeRotation, k);
            return Integer.compare(distA, distB);
        });
        for (int i = 0; i < remainder; i++) {
            counts[priorityOrder.get(i)]++;
        }

        Map<Integer, Integer> rawByModule = new LinkedHashMap<>();
        for (int i = 0; i < k; i++) {
            rawByModule.put(effectiveModules.get(i), counts[i]);
        }

        String breakdown = rawByModule.entrySet().stream()
                .map(e -> ModuleUnit.shortCode(e.getKey()) + ": " + e.getValue())
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
            throw new IllegalArgumentException("At least one module or half-module must be selected for INTERNAL exams");
        }
        List<Integer> sorted = requestedModules.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparingInt(ModuleUnit::sortOrder))
                .toList();
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("At least one module or half-module must be selected for INTERNAL exams");
        }
        Map<Integer, Integer> fullUnitSeenForBase = new HashMap<>();
        for (int mod : sorted) {
            if (!ModuleUnit.isValidUnit(mod)) {
                throw new IllegalArgumentException("Module unit must be 1-5 (full) or 11/12..51/52 (half), got: " + mod);
            }
            int base = ModuleUnit.baseModule(mod);
            if (ModuleUnit.isFull(mod)) {
                fullUnitSeenForBase.put(base, mod);
            } else if (fullUnitSeenForBase.containsKey(base)) {
                throw new IllegalArgumentException(
                        "Cannot combine " + ModuleUnit.displayName(base) + " and " + ModuleUnit.displayName(mod) + " in the same exam");
            }
        }
        return sorted;
    }
}
