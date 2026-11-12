package com.yourorg.qbank.core.importer;

import com.yourorg.qbank.core.model.FieldType;

import java.util.*;

public class HeaderDetector {

    private static final int MAX_HEADER_SCAN_ROWS = 10;
    private static final Map<String, FieldType> SYNONYMS = buildSynonymMap();

    public ColumnDetectionResult detect(RawSheetData sheetData) {
        List<RawRow> rows = sheetData.rows();
        int scanLimit = Math.min(MAX_HEADER_SCAN_ROWS, rows.size());

        CandidateHeader best = null;
        for (int rowIdx = 0; rowIdx < scanLimit; rowIdx++) {
            CandidateHeader candidate = evaluateRow(rows.get(rowIdx), rowIdx, sheetData.duplicateMergedColumns(), rows);
            if (candidate != null && (best == null || candidate.score() > best.score())) {
                best = candidate;
            }
        }

        if (best == null) {
            return new ColumnDetectionResult(
                    false, -1, -1, 0,
                    Map.of(), Map.of(), 0.0,
                    List.of("No header row matched in first " + scanLimit + " rows")
            );
        }

        double avgConfidence = best.fieldConfidence.values().stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);

        List<String> notes = new ArrayList<>();
        notes.add("Header row at row " + best.excelRowNumber);
        if (best.fieldToColumn.containsKey(FieldType.SERIAL)) {
            notes.add("Excluded serial/ID column #" + (best.fieldToColumn.get(FieldType.SERIAL) + 1));
        }

        return new ColumnDetectionResult(
                true,
                best.listIndex,
                best.excelRowNumber,
                best.listIndex + 1,
                Collections.unmodifiableMap(best.fieldToColumn),
                Collections.unmodifiableMap(best.fieldConfidence),
                avgConfidence,
                Collections.unmodifiableList(notes)
        );
    }

    private CandidateHeader evaluateRow(RawRow row, int listIndex, Set<Integer> ignoredCols, List<RawRow> allRows) {
        Map<FieldType, Integer> mapping = new EnumMap<>(FieldType.class);
        Map<FieldType, Double> confidences = new EnumMap<>(FieldType.class);

        for (int col = 0; col < row.cells().size(); col++) {
            if (ignoredCols.contains(col)) {
                continue;
            }
            String rawCell = row.cell(col);
            if (rawCell.isBlank()) {
                continue;
            }
            FieldMatch match = matchHeader(rawCell);
            if (match != null && !mapping.containsKey(match.fieldType)) {
                mapping.put(match.fieldType, col);
                confidences.put(match.fieldType, match.confidence);
            }
        }

        if (!mapping.containsKey(FieldType.QUESTION)) {
            return null;
        }

        int optionCount = countMatchedOptions(mapping);
        boolean hasAnswer = mapping.containsKey(FieldType.ANSWER);

        boolean validMultiColumnHeader = (optionCount >= 2) || (hasAnswer && mapping.size() >= 2);
        boolean validSingleColumnPacked = (optionCount == 0 && !hasAnswer && mapping.size() == 1
                && listIndex + 1 < allRows.size()
                && looksLikePackedSheetBelow(allRows, listIndex + 1, mapping.get(FieldType.QUESTION)));

        if (!validMultiColumnHeader && !validSingleColumnPacked) {
            return null;
        }

        return new CandidateHeader(listIndex, row.excelRowNumber(), mapping, confidences);
    }

    private boolean looksLikePackedSheetBelow(List<RawRow> rows, int startIdx, int questionCol) {
        int checked = 0;
        for (int i = startIdx; i < rows.size() && checked < 5; i++) {
            String text = rows.get(i).cell(questionCol);
            if (!text.isBlank()) {
                checked++;
                if (OneCellSplitter.containsPackedOptions(text)) {
                    return true;
                }
            }
        }
        return false;
    }

    private int countMatchedOptions(Map<FieldType, Integer> mapping) {
        int count = 0;
        for (FieldType opt : List.of(FieldType.OPT_A, FieldType.OPT_B, FieldType.OPT_C, FieldType.OPT_D, FieldType.OPT_E)) {
            if (mapping.containsKey(opt)) {
                count++;
            }
        }
        return count;
    }

    FieldMatch matchHeader(String rawHeader) {
        String normalized = normalizeHeaderText(rawHeader);
        if (normalized.isEmpty()) {
            return null;
        }
        FieldType exact = SYNONYMS.get(normalized);
        if (exact != null) {
            return new FieldMatch(exact, 1.0);
        }

        if (normalized.length() > 4) {
            FieldMatch bestFuzzy = null;
            for (Map.Entry<String, FieldType> entry : SYNONYMS.entrySet()) {
                String key = entry.getKey();
                if (key.length() <= 4 || Math.abs(key.length() - normalized.length()) > 1) {
                    continue;
                }
                int dist = levenshteinDistance(normalized, key);
                if (dist <= 1) {
                    bestFuzzy = new FieldMatch(entry.getValue(), 0.90);
                    break;
                }
            }
            if (bestFuzzy != null) {
                return bestFuzzy;
            }
        }
        return null;
    }

    static String normalizeHeaderText(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT)
                .replaceAll("[_()\\[\\]\\-\\./:]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    private static Map<String, FieldType> buildSynonymMap() {
        Map<String, FieldType> map = new LinkedHashMap<>();
        for (String s : List.of("s no", "sno", "sr no", "sr", "serial", "serial no", "serial number",
                "sl no", "sl number", "id", "q no", "qno", "question no", "question id", "num", "number", "#", "no")) {
            map.put(s, FieldType.SERIAL);
        }
        for (String s : List.of("question", "q", "ques", "question text", "q text", "stem", "problem", "prompt", "mcq", "question stem")) {
            map.put(s, FieldType.QUESTION);
        }
        for (String s : List.of("option a", "opt a", "opta", "opt1", "opt 1", "option 1", "option1", "a", "choice a", "choice 1", "1")) {
            map.put(s, FieldType.OPT_A);
        }
        for (String s : List.of("option b", "opt b", "optb", "opt2", "opt 2", "option 2", "option2", "b", "choice b", "choice 2", "2")) {
            map.put(s, FieldType.OPT_B);
        }
        for (String s : List.of("option c", "opt c", "optc", "opt3", "opt 3", "option 3", "option3", "c", "choice c", "choice 3", "3")) {
            map.put(s, FieldType.OPT_C);
        }
        for (String s : List.of("option d", "opt d", "optd", "opt4", "opt 4", "option 4", "option4", "d", "choice d", "choice 4", "4")) {
            map.put(s, FieldType.OPT_D);
        }
        for (String s : List.of("option e", "opt e", "opte", "opt5", "opt 5", "option 5", "option5", "e", "choice e", "choice 5", "5")) {
            map.put(s, FieldType.OPT_E);
        }
        for (String s : List.of("ans", "answer", "correct", "correct answer", "correct ans", "correct option",
                "key", "answer key", "right answer", "correct key", "solution")) {
            map.put(s, FieldType.ANSWER);
        }
        return Collections.unmodifiableMap(map);
    }

    record FieldMatch(FieldType fieldType, double confidence) {}

    private record CandidateHeader(
            int listIndex,
            int excelRowNumber,
            Map<FieldType, Integer> fieldToColumn,
            Map<FieldType, Double> fieldConfidence
    ) {
        double score() {
            long coreFields = fieldToColumn.keySet().stream().filter(f -> f != FieldType.SERIAL).count();
            double confSum = fieldConfidence.values().stream().mapToDouble(Double::doubleValue).sum();
            return coreFields * 10.0 + confSum;
        }
    }
}
