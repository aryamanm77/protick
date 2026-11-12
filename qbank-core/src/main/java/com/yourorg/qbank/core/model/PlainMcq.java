package com.yourorg.qbank.core.model;

import java.util.List;

/**
 * In-memory decrypted MCQ representation used strictly during module confirm shuffle,
 * paper generation, and internal exam-engine decryption. Never exposed on staff/student APIs.
 */
public record PlainMcq(
        String id,
        Long subjectId,
        int moduleNumber,
        int seq,
        String text,
        List<String> options,
        int correctIndex,
        int sourceRow
) {
}
