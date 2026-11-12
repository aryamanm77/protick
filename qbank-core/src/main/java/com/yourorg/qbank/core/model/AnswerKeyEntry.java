package com.yourorg.qbank.core.model;

/**
 * Internal answer key entry stored encrypted at rest and accessed only by FrozenPaperProvider / Scorer.
 */
public record AnswerKeyEntry(
        int position,
        String mcqId,
        int correctIndex,
        String correctLetter
) {
}
