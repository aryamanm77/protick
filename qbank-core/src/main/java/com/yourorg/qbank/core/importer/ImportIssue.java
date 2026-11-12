package com.yourorg.qbank.core.importer;

import com.yourorg.qbank.core.model.ImportSeverity;

public record ImportIssue(
        String sheet,
        int row,
        ImportSeverity severity,
        String message
) {
}
