package com.yourorg.qbank.core.port;

import com.yourorg.qbank.core.model.ExamStudent;

import java.util.List;
import java.util.Optional;

public interface ExamStudentRepository {
    ExamStudent save(ExamStudent student);
    Optional<ExamStudent> findBySessionAndRoll(long sessionId, String rollNumber);
    List<ExamStudent> findBySessionId(long sessionId);
    List<ExamStudent> findBySessionAndRoom(long sessionId, String roomCode);
    int maxExtraTimeForSession(long sessionId);
    long countAll();
}
