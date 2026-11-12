package com.yourorg.qbank.core.model;

import java.util.List;

/**
 * Generated paper version (A, B, C, or D) holding ordered questions and corresponding remapped answer key.
 */
public record GeneratedVersionData(
        String versionLabel,
        List<VersionQuestionSnapshot> questions,
        List<AnswerKeyEntry> answerKey
) {
}
