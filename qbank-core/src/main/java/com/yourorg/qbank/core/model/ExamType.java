package com.yourorg.qbank.core.model;

public enum ExamType {
    INTERNAL(30),
    SEE(50);

    private final int defaultTotalMarks;

    ExamType(int defaultTotalMarks) {
        this.defaultTotalMarks = defaultTotalMarks;
    }

    public int getDefaultTotalMarks() {
        return defaultTotalMarks;
    }
}
