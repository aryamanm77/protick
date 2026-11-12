package com.yourorg.qbank.core.model;

import java.util.List;
import java.util.Map;

/**
 * One of 4 disjoint generated paper sets containing all versions A-D sharing the same underlying MCQ IDs.
 */
public record GeneratedSetData(
        int setNumber,
        List<String> questionIds,
        Map<String, GeneratedVersionData> versions
) {
}
