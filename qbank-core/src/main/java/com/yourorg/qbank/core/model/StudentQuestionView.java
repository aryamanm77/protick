package com.yourorg.qbank.core.model;

import java.util.List;

/**
 * Sanitized question DTO delivered to authenticated active-session students.
 * Deliberately excludes MCQ IDs, module numbers, marks, hints, and correct answers.
 */
public record StudentQuestionView(
        int position,
        String text,
        List<String> options
) {
}
