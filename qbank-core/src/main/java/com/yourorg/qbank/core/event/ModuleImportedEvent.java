package com.yourorg.qbank.core.event;

import java.time.Instant;

public record ModuleImportedEvent(
        Long subjectId,
        String subjectCode,
        int moduleNumber,
        int savedCount,
        String importedBy,
        Instant occurredAt
) implements QBankEvent {
}
