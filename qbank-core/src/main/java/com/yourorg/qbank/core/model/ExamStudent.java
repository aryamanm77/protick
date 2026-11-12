package com.yourorg.qbank.core.model;

import java.time.Instant;

public record ExamStudent(
        Long id,
        Long sessionId,
        String rollNumber,
        String fullName,
        String roomCode,
        Integer rowNumber,
        Integer seatNumber,
        String assignedVersion,
        String accessCodeHash,
        StudentLoginState loginState,
        String activeTokenJti,
        double fontScale,
        boolean highContrast,
        int accommodationExtraMinutes,
        int grantedExtraMinutes,
        int lastQuestionIndex,
        Instant lastHeartbeatAt,
        Instant finishedAt,
        int version
) {
}
