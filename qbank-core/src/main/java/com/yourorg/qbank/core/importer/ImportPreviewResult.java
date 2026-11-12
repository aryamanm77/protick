package com.yourorg.qbank.core.importer;

import java.util.List;

public record ImportPreviewResult(
        String previewId,
        Long subjectId,
        String subjectCode,
        int moduleNumber,
        String filename,
        String sheetName,
        ColumnDetectionResult columnDetection,
        int totalRows,
        int validCount,
        int warningCount,
        int errorCount,
        int duplicateSkippedCount,
        int excludedCount,
        int expectedCount,
        boolean canConfirmDirectly,
        List<ImportIssue> issues,
        List<ParsedQuestionRow> rows
) {
}
