package com.yourorg.qbank.core.importer;

import com.yourorg.qbank.core.model.FieldType;
import com.yourorg.qbank.core.model.ImportSeverity;
import com.yourorg.qbank.core.port.CryptoPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pure Java Excel import analysis pipeline operating on parsed {@link RawSheetData}.
 * Returns preview rows strictly in original sheet order so the HOD can inspect and fix errors before confirmation.
 */
public class ExcelImportPipeline {

    private final HeaderDetector headerDetector = new HeaderDetector();
    private final ContentColumnDetector contentColumnDetector = new ContentColumnDetector();
    private final OneCellSplitter oneCellSplitter = new OneCellSplitter();
    private final Normalizer normalizer = new Normalizer();
    private final Validator validator = new Validator();
    private final CryptoPort cryptoPort;

    public ExcelImportPipeline(CryptoPort cryptoPort) {
        this.cryptoPort = cryptoPort;
    }

    public ImportPreviewResult preview(
            String previewId,
            Long subjectId,
            String subjectCode,
            int moduleNumber,
            String filename,
            RawSheetData sheetData,
            int expectedCount
    ) {
        ColumnDetectionResult detection = headerDetector.detect(sheetData);
        if (!detection.headerDetected()) {
            detection = contentColumnDetector.detect(sheetData);
        }

        List<ImportIssue> structuralIssues = new ArrayList<>();
        Map<FieldType, Integer> colMap = detection.fieldToColumn();
        if (!colMap.containsKey(FieldType.QUESTION)) {
            structuralIssues.add(new ImportIssue(
                    sheetData.sheetName(),
                    0,
                    ImportSeverity.ERROR,
                    "No usable Question column detected in sheet '" + sheetData.sheetName() + "'"
            ));
            return new ImportPreviewResult(
                    previewId, subjectId, subjectCode, moduleNumber, filename, sheetData.sheetName(),
                    detection, 0, 0, 0, 1, 0, 0, expectedCount, false,
                    structuralIssues, List.of()
            );
        }

        List<ParsedQuestionRow> rows = new ArrayList<>();
        List<RawRow> rawRows = sheetData.rows();
        int startIdx = detection.dataStartListIndex();
        for (int i = startIdx; i < rawRows.size(); i++) {
            RawRow rawRow = rawRows.get(i);
            ParsedQuestionRow parsed = parseRow(rawRow, colMap);
            if (parsed != null) {
                rows.add(parsed);
            }
        }

        Validator.ValidationSummary summary = validator.validate(
                sheetData.sheetName(), rows, detection, expectedCount
        );

        boolean canConfirmDirectly = summary.validCount() > 0 && summary.errorCount() == 0;
        return new ImportPreviewResult(
                previewId,
                subjectId,
                subjectCode,
                moduleNumber,
                filename,
                sheetData.sheetName(),
                detection,
                rows.size(),
                summary.validCount(),
                summary.warningCount(),
                summary.errorCount(),
                summary.duplicateSkippedCount(),
                summary.excludedCount(),
                expectedCount,
                canConfirmDirectly,
                summary.issues(),
                rows
        );
    }

    public Validator.ValidationSummary revalidateRows(String sheetName, List<ParsedQuestionRow> rows, ColumnDetectionResult detection, int expectedCount) {
        for (ParsedQuestionRow row : rows) {
            Normalizer.NormalizedRow re = normalizer.normalizeRow(
                    row.getSourceRow(),
                    row.getQuestionText(),
                    row.getOptions(),
                    row.getRawAnswerInput(),
                    cryptoPort
            );
            row.setQuestionText(re.questionText());
            row.setNormalizedHash(re.normalizedHash());
            row.setOptions(re.options());
            if (row.getCorrectIndex() == null || re.correctIndex() != null) {
                row.setCorrectIndex(re.correctIndex());
                row.setAnswerInferredFromText(re.inferredFromText());
            } else if (row.getCorrectIndex() >= 0 && row.getCorrectIndex() < re.options().size()) {
                row.setAnswerInferredFromText(false);
            } else {
                row.setCorrectIndex(null);
            }
        }
        return validator.validate(sheetName, rows, detection, expectedCount);
    }

    private ParsedQuestionRow parseRow(RawRow rawRow, Map<FieldType, Integer> colMap) {
        String rawQuestion = cellFor(rawRow, colMap.get(FieldType.QUESTION));
        List<String> rawOptions = new ArrayList<>();
        for (FieldType f : List.of(FieldType.OPT_A, FieldType.OPT_B, FieldType.OPT_C, FieldType.OPT_D, FieldType.OPT_E)) {
            Integer colIdx = colMap.get(f);
            if (colIdx != null) {
                String val = rawRow.cell(colIdx);
                if (!val.isBlank()) {
                    rawOptions.add(val);
                }
            }
        }
        String rawAnswer = cellFor(rawRow, colMap.get(FieldType.ANSWER));

        if (rawOptions.isEmpty() && OneCellSplitter.containsPackedOptions(rawQuestion)) {
            OneCellSplitter.SplitResult split = oneCellSplitter.split(rawQuestion);
            if (split.wasSplit()) {
                rawQuestion = split.questionText();
                rawOptions = split.options();
                if (rawAnswer.isBlank() && split.extractedAnswer() != null) {
                    rawAnswer = split.extractedAnswer();
                }
            }
        }

        if (rawQuestion.isBlank() && rawOptions.isEmpty() && rawAnswer.isBlank()) {
            return null;
        }

        Normalizer.NormalizedRow norm = normalizer.normalizeRow(
                rawRow.excelRowNumber(), rawQuestion, rawOptions, rawAnswer, cryptoPort
        );

        ParsedQuestionRow parsed = new ParsedQuestionRow();
        parsed.setSourceRow(norm.sourceRow());
        parsed.setQuestionText(norm.questionText());
        parsed.setNormalizedHash(norm.normalizedHash());
        parsed.setOptions(norm.options());
        parsed.setRawAnswerInput(norm.rawAnswerInput());
        parsed.setCorrectIndex(norm.correctIndex());
        parsed.setAnswerInferredFromText(norm.inferredFromText());
        return parsed;
    }

    private String cellFor(RawRow row, Integer colIdx) {
        return colIdx == null ? "" : row.cell(colIdx);
    }
}
