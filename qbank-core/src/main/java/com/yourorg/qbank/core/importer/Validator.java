package com.yourorg.qbank.core.importer;

import com.yourorg.qbank.core.model.ImportSeverity;

import java.util.*;

public class Validator {

    private static final double LOW_CONFIDENCE_THRESHOLD = 0.80;

    public ValidationSummary validate(
            String sheetName,
            List<ParsedQuestionRow> rows,
            ColumnDetectionResult columnDetection,
            int expectedCount
    ) {
        List<ImportIssue> allIssues = new ArrayList<>();

        if (columnDetection != null && (!columnDetection.headerDetected() || columnDetection.overallConfidence() < LOW_CONFIDENCE_THRESHOLD)) {
            String msg = String.format(Locale.ROOT,
                    "Low column mapping confidence (%.2f)%s",
                    columnDetection.overallConfidence(),
                    columnDetection.headerDetected() ? "" : " - headers inferred from content");
            allIssues.add(new ImportIssue(sheetName, 0, ImportSeverity.WARNING, msg));
        }

        Map<String, Integer> seenQuestionRows = new HashMap<>();
        int validCount = 0;
        int errorCount = 0;
        int duplicateSkippedCount = 0;
        int excludedCount = 0;

        for (ParsedQuestionRow row : rows) {
            row.setIssues(new ArrayList<>());
            row.setDuplicateSkipped(false);

            if (row.isExcluded()) {
                row.setStatus(ParsedQuestionRow.RowStatus.EXCLUDED);
                excludedCount++;
                ImportIssue issue = new ImportIssue(sheetName, row.getSourceRow(), ImportSeverity.INFO,
                        "Row " + row.getSourceRow() + ": excluded");
                row.getIssues().add(issue);
                allIssues.add(issue);
                continue;
            }

            List<ImportIssue> rowErrors = collectRowErrors(sheetName, row);
            if (!rowErrors.isEmpty()) {
                row.setStatus(ParsedQuestionRow.RowStatus.ERROR);
                row.getIssues().addAll(rowErrors);
                allIssues.addAll(rowErrors);
                errorCount++;
                continue;
            }

            Integer firstRow = seenQuestionRows.get(row.getNormalizedHash());
            if (firstRow != null) {
                row.setDuplicateSkipped(true);
                row.setStatus(ParsedQuestionRow.RowStatus.DUPLICATE_SKIPPED);
                ImportIssue dupIssue = new ImportIssue(sheetName, row.getSourceRow(), ImportSeverity.WARNING,
                        "Row " + row.getSourceRow() + ": duplicate of row " + firstRow + " (skipped)");
                row.getIssues().add(dupIssue);
                allIssues.add(dupIssue);
                duplicateSkippedCount++;
                continue;
            }
            seenQuestionRows.put(row.getNormalizedHash(), row.getSourceRow());

            if (row.isAnswerInferredFromText()) {
                row.setStatus(ParsedQuestionRow.RowStatus.WARNING);
                ImportIssue infIssue = new ImportIssue(sheetName, row.getSourceRow(), ImportSeverity.WARNING,
                        "Row " + row.getSourceRow() + ": answer matched by option text");
                row.getIssues().add(infIssue);
                allIssues.add(infIssue);
            } else {
                row.setStatus(ParsedQuestionRow.RowStatus.VALID);
            }

            validCount++;
        }

        if (expectedCount > 0 && validCount != expectedCount) {
            allIssues.add(new ImportIssue(sheetName, 0, ImportSeverity.WARNING,
                    "Count " + validCount + " differs from expected " + expectedCount));
        }

        int warningCount = (int) allIssues.stream()
                .filter(i -> i.severity() == ImportSeverity.WARNING)
                .count();

        return new ValidationSummary(validCount, warningCount, errorCount, duplicateSkippedCount, excludedCount, allIssues);
    }

    private List<ImportIssue> collectRowErrors(String sheetName, ParsedQuestionRow row) {
        List<ImportIssue> errors = new ArrayList<>();
        int r = row.getSourceRow();
        if (row.getQuestionText() == null || row.getQuestionText().isBlank()) {
            errors.add(new ImportIssue(sheetName, r, ImportSeverity.ERROR, "Row " + r + ": empty question"));
        }
        List<String> options = row.getOptions();
        if (options == null || options.size() < 2) {
            errors.add(new ImportIssue(sheetName, r, ImportSeverity.ERROR, "Row " + r + ": fewer than 2 options"));
        }
        Integer correctIdx = row.getCorrectIndex();
        if (correctIdx == null || options == null || correctIdx < 0 || correctIdx >= options.size()) {
            String rawAns = row.getRawAnswerInput() == null ? "" : row.getRawAnswerInput().trim();
            String msg = rawAns.isEmpty()
                    ? "Row " + r + ": no answer"
                    : "Row " + r + ": bad answer '" + rawAns + "'";
            errors.add(new ImportIssue(sheetName, r, ImportSeverity.ERROR, msg));
        }
        return errors;
    }

    public record ValidationSummary(
            int validCount,
            int warningCount,
            int errorCount,
            int duplicateSkippedCount,
            int excludedCount,
            List<ImportIssue> issues
    ) {
    }
}
