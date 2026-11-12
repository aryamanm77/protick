package com.yourorg.qbank.core.importer;

import com.yourorg.qbank.core.model.ModuleUnit;
import com.yourorg.qbank.core.model.PlainMcq;
import com.yourorg.qbank.core.port.RandomPort;

import java.util.*;

/**
 * Executed on HOD import confirmation: randomizes both question order and per-question option order
 * (remapping correctIndex) with a securely drawn seed, and assigns internal traceability IDs in the
 * newly shuffled bank order so original Excel ordering never leaks downstream.
 */
public class ModuleShuffleAssigner {

    public List<PlainMcq> shuffleAndAssignIds(
            Long subjectId,
            String subjectCode,
            int moduleNumber,
            List<ParsedQuestionRow> validRows,
            RandomPort randomPort
    ) {
        Objects.requireNonNull(validRows, "validRows must not be null");
        String normalizedCode = subjectCode.trim().toUpperCase(Locale.ROOT);

        List<ParsedQuestionRow> kept = validRows.stream()
                .filter(this::isKept)
                .toList();

        long seed = randomPort.nextLong();
        Random rng = randomPort.createSeededRandom(seed);

        List<ParsedQuestionRow> shuffledQuestions = new ArrayList<>(kept);
        Collections.shuffle(shuffledQuestions, rng);
        // Ensure a multi-question bank strictly differs from original sheet order when possible
        if (shuffledQuestions.size() >= 2 && sameOrder(kept, shuffledQuestions)) {
            Collections.swap(shuffledQuestions, 0, shuffledQuestions.size() - 1);
        }

        List<PlainMcq> result = new ArrayList<>(shuffledQuestions.size());
        int seq = 1;
        for (ParsedQuestionRow row : shuffledQuestions) {
            ShuffledOptions shuffledOpts = shuffleOptions(row.getOptions(), row.getCorrectIndex(), rng);
            String id = formatId(normalizedCode, moduleNumber, seq);
            result.add(new PlainMcq(
                    id,
                    subjectId,
                    moduleNumber,
                    seq,
                    row.getQuestionText(),
                    shuffledOpts.options(),
                    shuffledOpts.correctIndex(),
                    row.getSourceRow()
            ));
            seq++;
        }
        return result;
    }

    private boolean sameOrder(List<ParsedQuestionRow> original, List<ParsedQuestionRow> candidate) {
        if (original.size() != candidate.size()) {
            return false;
        }
        for (int i = 0; i < original.size(); i++) {
            if (original.get(i).getSourceRow() != candidate.get(i).getSourceRow()) {
                return false;
            }
        }
        return true;
    }

    private ShuffledOptions shuffleOptions(List<String> originalOptions, int originalCorrectIndex, Random rng) {
        List<IndexedOption> indexed = new ArrayList<>(originalOptions.size());
        for (int i = 0; i < originalOptions.size(); i++) {
            indexed.add(new IndexedOption(originalOptions.get(i), i == originalCorrectIndex));
        }
        Collections.shuffle(indexed, rng);

        List<String> shuffled = new ArrayList<>(indexed.size());
        int newCorrect = 0;
        for (int i = 0; i < indexed.size(); i++) {
            IndexedOption opt = indexed.get(i);
            shuffled.add(opt.text());
            if (opt.correct()) {
                newCorrect = i;
            }
        }
        return new ShuffledOptions(Collections.unmodifiableList(shuffled), newCorrect);
    }

    public static String formatId(String subjectCode, int moduleNumber, int seq) {
        return String.format(Locale.ROOT, "%s-%s-%03d",
                subjectCode.trim().toUpperCase(Locale.ROOT),
                ModuleUnit.shortCode(moduleNumber),
                seq);
    }

    private boolean isKept(ParsedQuestionRow row) {
        return !row.isExcluded()
                && !row.isDuplicateSkipped()
                && row.getStatus() != ParsedQuestionRow.RowStatus.ERROR
                && row.getStatus() != ParsedQuestionRow.RowStatus.EXCLUDED;
    }

    private record IndexedOption(String text, boolean correct) {}

    private record ShuffledOptions(List<String> options, int correctIndex) {}
}
