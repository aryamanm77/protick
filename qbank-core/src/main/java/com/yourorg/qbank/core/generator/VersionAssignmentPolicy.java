package com.yourorg.qbank.core.generator;

import java.util.List;

public interface VersionAssignmentPolicy {
    String assignVersion(String rollNumber, Integer rowIndex, Integer seatIndex, List<String> availableVersions);
}
