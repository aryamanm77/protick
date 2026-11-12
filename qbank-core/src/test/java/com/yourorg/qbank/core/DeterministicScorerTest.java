package com.yourorg.qbank.core;

import com.yourorg.qbank.core.model.AnswerKeyEntry;
import com.yourorg.qbank.core.model.ExamStudent;
import com.yourorg.qbank.core.model.StudentLoginState;
import com.yourorg.qbank.core.model.StudentResponseRecord;
import com.yourorg.qbank.core.model.StudentScoreResult;
import com.yourorg.qbank.core.scoring.DeterministicScorer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeterministicScorerTest {

    @Test
    void unansweredQuestionsAreNeverTreatedAsWrongAndScoringIsBitIdentical() {
        ExamStudent student = new ExamStudent(
                1L, 10L, "CS26-001", "Asha Rao", "R101", 1, 1, "B",
                "hash", StudentLoginState.FINISHED, null, 1.0, false, 0, 0, 4,
                Instant.parse("2026-11-11T10:00:00Z"), Instant.parse("2026-11-11T10:05:00Z"), 1);

        List<AnswerKeyEntry> key = List.of(
                new AnswerKeyEntry(1, "Q1", 0, "A"),
                new AnswerKeyEntry(2, "Q2", 2, "C"),
                new AnswerKeyEntry(3, "Q3", 1, "B"),
                new AnswerKeyEntry(4, "Q4", 3, "D"),
                new AnswerKeyEntry(5, "Q5", 0, "A")
        );

        // Q1 right (0), Q2 wrong (1 instead of 2), Q3 null (explicitly cleared), Q4 right (3), Q5 missing row
        List<StudentResponseRecord> responses = List.of(
                new StudentResponseRecord(1L, 10L, "CS26-001", 1, 0, "B", Instant.now(), Instant.now()),
                new StudentResponseRecord(2L, 10L, "CS26-001", 2, 1, "B", Instant.now(), Instant.now()),
                new StudentResponseRecord(3L, 10L, "CS26-001", 3, null, "B", Instant.now(), Instant.now()),
                new StudentResponseRecord(4L, 10L, "CS26-001", 4, 3, "B", Instant.now(), Instant.now())
        );

        StudentScoreResult r1 = DeterministicScorer.score(student, 2, key, responses);
        StudentScoreResult r2 = DeterministicScorer.score(student, 2, key, responses);

        assertEquals(r1, r2);
        assertEquals(5, r1.totalQuestions());
        assertEquals(2, r1.correctCount());
        assertEquals(1, r1.wrongCount());
        assertEquals(2, r1.unansweredCount());
        assertEquals(4, r1.marksAwarded());
        assertEquals(10, r1.maxMarks());
    }
}
