package com.yourorg.qbank.core.model;

import java.util.List;

public record PaperSetStored(
        Long id,
        Long sessionId,
        int setNumber,
        PaperSetStatus status,
        int questionCount,
        List<String> questionIds
) {
}
