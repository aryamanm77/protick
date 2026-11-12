package com.yourorg.qbank.core.model;

import java.util.List;

/**
 * Engine-only snapshot of an exam question within a specific version A-D.
 */
public record VersionQuestionSnapshot(
        int position,
        String mcqId,
        int moduleNumber,
        String text,
        List<String> options
) {
}
