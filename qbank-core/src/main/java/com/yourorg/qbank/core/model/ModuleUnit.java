package com.yourorg.qbank.core.model;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encodes and formats full modules (1..5) and half-module units (11/12..51/52).
 *
 * <p>Unit numbering stays backward-compatible with integer module columns:
 * <ul>
 *   <li>Full module m (1..5): code m (weight = 2 halves = 1.0 module)</li>
 *   <li>Module m First Half (Part A): m * 10 + 1, e.g. 21 = M2A (weight = 1 = 0.5 module)</li>
 *   <li>Module m Second Half (Part B): m * 10 + 2, e.g. 22 = M2B (weight = 1 = 0.5 module)</li>
 * </ul>
 */
public final class ModuleUnit {

    private static final Pattern FULL_SHEET_PATTERN = Pattern.compile(
            "^(?:module|mod|m)[\\s_-]*([1-5])$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern HALF_A_SHEET_PATTERN = Pattern.compile(
            "^(?:module|mod|m)[\\s_-]*([1-5])(?:[\\s_.-]*(?:a|p1|part[\\s_-]*(?:1|a)|h1|half[\\s_-]*1|1st[\\s_-]*half|first[\\s_-]*half|\\(1st[\\s_-]*half\\)|\\(part[\\s_-]*(?:1|a)\\)|1))$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern HALF_B_SHEET_PATTERN = Pattern.compile(
            "^(?:module|mod|m)[\\s_-]*([1-5])(?:[\\s_.-]*(?:b|p2|part[\\s_-]*(?:2|b)|h2|half[\\s_-]*2|2nd[\\s_-]*half|second[\\s_-]*half|\\(2nd[\\s_-]*half\\)|\\(part[\\s_-]*(?:2|b)\\)|2))$",
            Pattern.CASE_INSENSITIVE
    );

    private ModuleUnit() {
    }

    public static boolean isValidUnit(int unit) {
        if (unit >= 1 && unit <= 5) {
            return true;
        }
        int base = unit / 10;
        int part = unit % 10;
        return base >= 1 && base <= 5 && (part == 1 || part == 2);
    }

    public static boolean isFull(int unit) {
        return unit >= 1 && unit <= 5;
    }

    public static boolean isHalf(int unit) {
        return isValidUnit(unit) && !isFull(unit);
    }

    public static int baseModule(int unit) {
        return isFull(unit) ? unit : unit / 10;
    }

    /** Returns 0 for full module, 1 for first half (Part A), 2 for second half (Part B). */
    public static int part(int unit) {
        return isFull(unit) ? 0 : unit % 10;
    }

    public static int firstHalf(int baseModule) {
        return baseModule * 10 + 1;
    }

    public static int secondHalf(int baseModule) {
        return baseModule * 10 + 2;
    }

    /** Relative blueprint weight measured in half-modules: Full = 2, Half = 1. */
    public static int weight(int unit) {
        return isFull(unit) ? 2 : 1;
    }

    /** Natural ascending display/sort order: M1(10), M1A(11), M1B(12), M2(20), M2A(21), M2B(22)... */
    public static int sortOrder(int unit) {
        return baseModule(unit) * 10 + part(unit);
    }

    public static String shortCode(int unit) {
        int base = baseModule(unit);
        return switch (part(unit)) {
            case 1 -> "M" + base + "A";
            case 2 -> "M" + base + "B";
            default -> "M" + base;
        };
    }

    public static String displayName(int unit) {
        int base = baseModule(unit);
        return switch (part(unit)) {
            case 1 -> "Module " + base + " (1st Half)";
            case 2 -> "Module " + base + " (2nd Half)";
            default -> "Module " + base;
        };
    }

    public static int expectedCount(int unit, int expectedPerFullModule) {
        if (isFull(unit)) {
            return expectedPerFullModule;
        }
        return Math.max(1, (expectedPerFullModule + 1) / 2);
    }

    /**
     * Returns true when replacing/examining {@code unitA} touches questions overlapping {@code unitB}.
     * Crucially, First Half (e.g. 21) and Second Half (22) of the same module do NOT overlap.
     */
    public static boolean overlaps(int unitA, int unitB) {
        if (!isValidUnit(unitA) || !isValidUnit(unitB)) {
            return false;
        }
        if (unitA == unitB) {
            return true;
        }
        if (baseModule(unitA) != baseModule(unitB)) {
            return false;
        }
        return isFull(unitA) || isFull(unitB);
    }

    /**
     * Parses Excel sheet names such as "Module 1".."Module 5", "M1", "Module 2A", "Module 2 Part 1",
     * "Module 2 (1st Half)", "M3B", "M3.2", "Module 4 Second Half".
     */
    public static Integer parseSheetUnit(String sheetName) {
        if (sheetName == null || sheetName.isBlank()) {
            return null;
        }
        String clean = sheetName.trim().replaceAll("\\s+", " ");
        Matcher full = FULL_SHEET_PATTERN.matcher(clean);
        if (full.matches()) {
            return Integer.parseInt(full.group(1));
        }
        Matcher halfA = HALF_A_SHEET_PATTERN.matcher(clean);
        if (halfA.matches()) {
            return firstHalf(Integer.parseInt(halfA.group(1)));
        }
        Matcher halfB = HALF_B_SHEET_PATTERN.matcher(clean);
        if (halfB.matches()) {
            return secondHalf(Integer.parseInt(halfB.group(1)));
        }
        String upper = clean.toUpperCase(Locale.ROOT);
        if (upper.matches("[1-5][12]")) {
            return Integer.parseInt(upper);
        }
        return null;
    }
}
