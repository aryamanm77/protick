package com.yourorg.qbank.core.model;

public record StudentScoreResult(
        String rollNumber,
        String fullName,
        String roomCode,
        String assignedVersion,
        int totalQuestions,
        int correctCount,
        int wrongCount,
        int unansweredCount,
        int marksAwarded,
        int maxMarks
) {
}
