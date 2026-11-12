package com.yourorg.qbank.core.model;

/**
 * Minimal card returned to COE during set selection. Never includes question IDs, stems, options, or answers.
 */
public record SetSummaryCard(
        int setNumber,
        int questionCount,
        PaperSetStatus status
) {
}
