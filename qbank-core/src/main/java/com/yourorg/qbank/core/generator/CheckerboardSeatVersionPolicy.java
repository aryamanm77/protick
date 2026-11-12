package com.yourorg.qbank.core.generator;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CheckerboardSeatVersionPolicy implements VersionAssignmentPolicy {

    private static final Pattern TRAILING_DIGITS = Pattern.compile("(\\d+)$");

    @Override
    public String assignVersion(String rollNumber, Integer rowIndex, Integer seatIndex, List<String> availableVersions) {
        if (availableVersions == null || availableVersions.isEmpty()) {
            throw new IllegalArgumentException("availableVersions must not be empty");
        }
        int k = availableVersions.size();
        if (rowIndex != null && seatIndex != null) {
            int idx = Math.floorMod(rowIndex * 2 + seatIndex, k);
            return availableVersions.get(idx);
        }
        int numericRoll = extractRollSequence(rollNumber);
        int idx = Math.floorMod(numericRoll, k);
        return availableVersions.get(idx);
    }

    private int extractRollSequence(String rollNumber) {
        if (rollNumber == null || rollNumber.isBlank()) {
            throw new IllegalArgumentException("rollNumber must not be blank when seat coordinates are omitted");
        }
        String trimmed = rollNumber.trim();
        Matcher m = TRAILING_DIGITS.matcher(trimmed);
        if (m.find()) {
            String digits = m.group(1);
            if (digits.length() > 6) {
                digits = digits.substring(digits.length() - 6);
            }
            return Integer.parseInt(digits);
        }
        return Math.abs(trimmed.hashCode());
    }
}
