package com.yourorg.qbank.core.importer;

import com.yourorg.qbank.core.model.FieldType;

import java.util.*;
import java.util.regex.Pattern;

public class ContentColumnDetector {

    private static final Pattern ANSWER_TOKEN = Pattern.compile("^[A-Da-d1-4]$");
    private static final Pattern POSITIVE_INT = Pattern.compile("^\\d{1,6}$");

    public ColumnDetectionResult detect(RawSheetData sheetData) {
        List<RawRow> rows = sheetData.rows();
        int colCount = sheetData.columnCount();
        Set<Integer> duplicateMerged = sheetData.duplicateMergedColumns();

        if (rows.isEmpty() || colCount == 0) {
            return new ColumnDetectionResult(false, -1, -1, 0, Map.of(), Map.of(), 0.0, List.of("Sheet contains no data rows"));
        }

        List<Integer> activeCols = new ArrayList<>();
        for (int c = 0; c < colCount; c++) {
            if (!duplicateMerged.contains(c) && hasAnyNonBlank(rows, c)) {
                activeCols.add(c);
            }
        }

        if (activeCols.isEmpty()) {
            return new ColumnDetectionResult(false, -1, -1, 0, Map.of(), Map.of(), 0.0, List.of("No non-empty columns found"));
        }

        Map<FieldType, Integer> mapping = new EnumMap<>(FieldType.class);
        Map<FieldType, Double> confidences = new EnumMap<>(FieldType.class);
        List<String> notes = new ArrayList<>();
        notes.add("No header row matched; columns inferred from cell content patterns");

        Set<Integer> assigned = new HashSet<>();

        Integer serialCol = findSerialColumn(rows, activeCols);
        if (serialCol != null) {
            mapping.put(FieldType.SERIAL, serialCol);
            confidences.put(FieldType.SERIAL, 0.90);
            assigned.add(serialCol);
            notes.add("Excluded serial/ID column #" + (serialCol + 1));
        }

        AnswerCandidate ans = findAnswerColumn(rows, activeCols, assigned);
        if (ans != null) {
            mapping.put(FieldType.ANSWER, ans.colIndex);
            confidences.put(FieldType.ANSWER, ans.ratio);
            assigned.add(ans.colIndex);
        }

        Integer questionCol = findQuestionColumn(rows, activeCols, assigned);
        if (questionCol != null) {
            mapping.put(FieldType.QUESTION, questionCol);
            boolean singleColPacked = activeCols.size() - assigned.size() == 1;
            confidences.put(FieldType.QUESTION, singleColPacked ? 0.72 : 0.80);
            assigned.add(questionCol);
        }

        List<Integer> remainingOptionCols = activeCols.stream()
                .filter(c -> !assigned.contains(c))
                .sorted()
                .toList();

        FieldType[] optFields = {FieldType.OPT_A, FieldType.OPT_B, FieldType.OPT_C, FieldType.OPT_D, FieldType.OPT_E};
        for (int i = 0; i < Math.min(remainingOptionCols.size(), optFields.length); i++) {
            int colIdx = remainingOptionCols.get(i);
            mapping.put(optFields[i], colIdx);
            confidences.put(optFields[i], 0.72);
            assigned.add(colIdx);
        }

        double overall = confidences.entrySet().stream()
                .filter(e -> e.getKey() != FieldType.SERIAL)
                .mapToDouble(Map.Entry::getValue)
                .average()
                .orElse(0.0);

        return new ColumnDetectionResult(
                false, -1, -1, 0,
                Collections.unmodifiableMap(mapping),
                Collections.unmodifiableMap(confidences),
                overall,
                Collections.unmodifiableList(notes)
        );
    }

    private boolean hasAnyNonBlank(List<RawRow> rows, int col) {
        return rows.stream().anyMatch(r -> !r.cell(col).isBlank());
    }

    private Integer findSerialColumn(List<RawRow> rows, List<Integer> activeCols) {
        for (int col : activeCols) {
            List<Integer> nums = new ArrayList<>();
            int nonBlank = 0;
            boolean valid = true;
            for (RawRow r : rows) {
                String v = r.cell(col).trim();
                if (v.isEmpty()) continue;
                nonBlank++;
                if (!POSITIVE_INT.matcher(v).matches()) {
                    valid = false;
                    break;
                }
                nums.add(Integer.parseInt(v));
            }
            if (!valid || nonBlank < 2) continue;

            boolean strictlyIncreasing = true;
            for (int i = 1; i < nums.size(); i++) {
                if (nums.get(i) <= nums.get(i - 1)) {
                    strictlyIncreasing = false;
                    break;
                }
            }
            if (strictlyIncreasing) {
                double maxRatio = nums.stream().filter(n -> n >= 1 && n <= 4).count() / (double) nums.size();
                if (nums.size() <= 4 && maxRatio > 0.90 && activeCols.size() >= 4) {
                    continue;
                }
                return col;
            }
        }
        return null;
    }

    private AnswerCandidate findAnswerColumn(List<RawRow> rows, List<Integer> activeCols, Set<Integer> assigned) {
        AnswerCandidate best = null;
        for (int col : activeCols) {
            if (assigned.contains(col)) continue;
            int nonBlank = 0;
            int matches = 0;
            for (RawRow r : rows) {
                String v = r.cell(col).trim();
                if (v.isEmpty()) continue;
                nonBlank++;
                if (ANSWER_TOKEN.matcher(v).matches()) matches++;
            }
            if (nonBlank == 0) continue;
            double ratio = (double) matches / nonBlank;
            if (ratio > 0.90 && (best == null || ratio > best.ratio || (ratio == best.ratio && col > best.colIndex))) {
                best = new AnswerCandidate(col, ratio);
            }
        }
        return best;
    }

    private Integer findQuestionColumn(List<RawRow> rows, List<Integer> activeCols, Set<Integer> assigned) {
        Integer bestCol = null;
        double bestAvg = -1.0;
        for (int col : activeCols) {
            if (assigned.contains(col)) continue;
            int nonBlank = 0;
            long totalLen = 0;
            for (RawRow r : rows) {
                String v = r.cell(col).trim();
                if (!v.isEmpty()) {
                    nonBlank++;
                    totalLen += v.length();
                }
            }
            if (nonBlank == 0) continue;
            double avg = (double) totalLen / nonBlank;
            if (avg > bestAvg) {
                bestAvg = avg;
                bestCol = col;
            }
        }
        return bestCol;
    }

    private record AnswerCandidate(int colIndex, double ratio) {}
}
