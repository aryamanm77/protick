package com.yourorg.qbank.core.importer;

import java.util.List;

public record RawRow(
        int excelRowNumber,
        List<String> cells
) {
    public String cell(int index) {
        if (index < 0 || index >= cells.size()) {
            return "";
        }
        String val = cells.get(index);
        return val == null ? "" : val;
    }
}
