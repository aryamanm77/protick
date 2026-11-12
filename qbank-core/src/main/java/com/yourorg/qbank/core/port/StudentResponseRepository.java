package com.yourorg.qbank.core.port;

import com.yourorg.qbank.core.model.StudentResponseRecord;

import java.time.Instant;
import java.util.List;

public interface StudentResponseRepository {
    StudentResponseRecord upsertResponse(StudentResponseRecord record);
    List<StudentResponseRecord> findBySessionAndRoll(long sessionId, String rollNumber);
    List<StudentResponseRecord> findBySessionId(long sessionId);
    long countAll();
    int deleteResponsesForEndedSessionsBefore(Instant cutoff, String adminActor, String reason);
}
