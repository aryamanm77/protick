package com.yourorg.qbank.core.model;

import java.time.Instant;

public record SubjectModule(
        Long id,
        Long subjectId,
        String subjectCode,
        int moduleNumber,
        ModuleStatus status,
        int questionCount,
        String lastImportFilename,
        String lastImportedBy,
        Instant lastImportedAt,
        int lastErrorCount,
        int lastWarningCount
) {
}
