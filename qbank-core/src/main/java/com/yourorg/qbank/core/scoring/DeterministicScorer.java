package com.yourorg.qbank.core.scoring;

import com.yourorg.qbank.core.model.AnswerKeyEntry;
import com.yourorg.qbank.core.model.ExamStudent;
import com.yourorg.qbank.core.model.StudentResponseRecord;
import com.yourorg.qbank.core.model.StudentScoreResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, deterministic MCQ scoring function.
 * Guarantees unanswered questions are counted distinctly as unanswered (never marked wrong or guessed),
 * and repeated runs over the same inputs always yield bit-identical results.
 */
public final class DeterministicScorer {

    private DeterministicScorer() {
    }

    public static StudentScoreResult score(
            ExamStudent student,
            int marksPerQuestion,
            List<AnswerKeyEntry> answerKey,
            List<StudentResponseRecord> studentResponses
    ) {
        if (marksPerQuestion <= 0) {
            throw new IllegalArgumentException("marksPerQuestion must be positive");
        }
        if (answerKey == null || answerKey.isEmpty()) {
            throw new IllegalArgumentException("answerKey must not be empty");
        }

        Map<Integer, Integer> selectedByPos = new HashMap<>();
        if (studentResponses != null) {
            for (StudentResponseRecord rec : studentResponses) {
                selectedByPos.put(rec.questionPosition(), rec.selectedOptionIndex());
            }
        }

        int total = answerKey.size();
        int correct = 0;
        int wrong = 0;
        int unanswered = 0;

        for (AnswerKeyEntry keyEntry : answerKey) {
            Integer chosen = selectedByPos.get(keyEntry.position());
            if (chosen == null) {
                unanswered++;
            } else if (chosen == keyEntry.correctIndex()) {
                correct++;
            } else {
                wrong++;
            }
        }

        int marksAwarded = correct * marksPerQuestion;
        int maxMarks = total * marksPerQuestion;

        return new StudentScoreResult(
                student.rollNumber(),
                student.fullName(),
                student.roomCode(),
                student.assignedVersion(),
                total,
                correct,
                wrong,
                unanswered,
                marksAwarded,
                maxMarks
        );
    }
}
