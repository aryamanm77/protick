package com.yourorg.qbank.core.importer;

import com.yourorg.qbank.core.model.FieldType;

import java.util.List;
import java.util.Map;

public record ColumnDetectionResult(
        boolean headerDetected,
        int headerRowListIndex,
        int headerExcelRowNumber,
        int dataStartListIndex,
        Map<FieldType, Integer> fieldToColumn,
        Map<FieldType, Double> fieldConfidence,
        double overallConfidence,
        List<String> notes
) {
}
