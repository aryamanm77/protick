package com.yourorg.qbank.core.security;

/**
 * Prevents spreadsheet formula injection (CSV/Excel macro execution) on exported cell values
 * and ensures safe CSV field quoting.
 */
public final class FormulaSanitizer {

    private FormulaSanitizer() {
    }

    /**
     * Neutralizes values whose first non-whitespace character triggers spreadsheet formula evaluation
     * ({@code =}, {@code +}, {@code -}, {@code @}) or control characters ({@code \t}, {@code \r}).
     */
    public static String neutralizeSpreadsheetCell(String rawValue) {
        if (rawValue == null || rawValue.isEmpty()) {
            return "";
        }
        char first = rawValue.charAt(0);
        String stripped = rawValue.stripLeading();
        char firstNonWs = stripped.isEmpty() ? first : stripped.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r'
                || firstNonWs == '=' || firstNonWs == '+' || firstNonWs == '-' || firstNonWs == '@') {
            return "'" + rawValue;
        }
        return rawValue;
    }

    public static String escapeCsvField(String rawValue) {
        String safe = neutralizeSpreadsheetCell(rawValue);
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }
}
