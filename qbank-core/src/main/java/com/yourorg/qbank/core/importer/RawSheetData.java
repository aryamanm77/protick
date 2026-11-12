package com.yourorg.qbank.core.importer;

import java.util.List;
import java.util.Set;

public record RawSheetData(
        String sheetName,
        int columnCount,
        List<RawRow> rows,
        Set<Integer> duplicateMergedColumns
) {
}
