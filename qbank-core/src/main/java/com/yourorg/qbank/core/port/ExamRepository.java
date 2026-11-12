package com.yourorg.qbank.core.port;

import com.yourorg.qbank.core.model.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface ExamRepository {
    Exam saveExam(Exam exam);
    Optional<Exam> findExamById(long examId);
    List<Exam> findAllExams();
    List<Exam> findExamsBySubjectId(long subjectId);
    long countExams();

    ExamSession saveSession(ExamSession session);
    Optional<ExamSession> findSessionById(long sessionId);
    List<ExamSession> findSessionsByExamId(long examId);
    List<ExamSession> findAllSessions();
    List<ExamSession> findFrozenOrLiveSessionsUsingModule(long subjectId, int moduleNumber);

    PaperSetStored savePaperSet(PaperSetStored paperSet);
    List<PaperSetStored> findSetsBySessionId(long sessionId);
    void updatePaperSetStatus(long setId, PaperSetStatus status);
    Set<String> findQuestionIdsUsedInSession(long sessionId);

    void savePaperVersion(PaperVersionStored version);
    Optional<PaperVersionStored> findSelectedVersion(long sessionId, String versionLabel);
    List<PaperVersionStored> findVersionsBySetId(long paperSetId);

    boolean updateSessionStatusWithVersion(
            long sessionId,
            int expectedVersion,
            SessionStatus newStatus,
            Integer chosenSetNumber,
            Instant actualStartedAt,
            Instant actualEndedAt,
            String startedBy,
            String endedBy,
            String frozenBy,
            Instant frozenAt,
            String contentSha256
    );
}
