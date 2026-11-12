package com.yourorg.qbank.core.generator;

import com.yourorg.qbank.core.exception.BadRequestException;
import com.yourorg.qbank.core.model.AnswerKeyEntry;
import com.yourorg.qbank.core.model.GeneratedSetData;
import com.yourorg.qbank.core.model.GeneratedVersionData;
import com.yourorg.qbank.core.model.PlainMcq;
import com.yourorg.qbank.core.model.VersionQuestionSnapshot;
import com.yourorg.qbank.core.port.RandomPort;

import java.util.*;

/**
 * Generates pairwise disjoint exam sets across both MORNING and AFTERNOON sessions
 * (up to 8 disjoint chunks of size k per module: chunks 0..3 for Morning Sets 1..4,
 * chunks 4..7 for Afternoon Sets 1..4), each with conflict-free shuffled Versions A-D
 * and remapped option indices.
 */
public class PaperSetVersionGenerator {

    public static final int SETS_PER_SESSION = 4;
    public static final List<String> VERSION_LABELS = List.of("A", "B", "C", "D");

    public DualSessionGeneration generateDualSessions(
            Map<Integer, List<PlainMcq>> poolByModule,
            BlueprintResult blueprint,
            long seed,
            boolean includeAfternoon,
            RandomPort randomPort
    ) {
        int chunksNeeded = includeAfternoon ? 8 : SETS_PER_SESSION;
        List<Map<Integer, List<PlainMcq>>> chunkMaps = new ArrayList<>(chunksNeeded);
        for (int s = 0; s < chunksNeeded; s++) {
            chunkMaps.add(new LinkedHashMap<>());
        }

        for (Integer modNum : blueprint.selectedModules()) {
            int requiredPerSet = blueprint.moduleCounts().getOrDefault(modNum, 0);
            List<PlainMcq> modulePool = poolByModule.getOrDefault(modNum, List.of());
            int requiredTotal = chunksNeeded * requiredPerSet;
            if (modulePool.size() < requiredTotal) {
                throw new BadRequestException(String.format(
                        Locale.ROOT,
                        "Module %d has %d questions, needs %d. Add questions or pick fewer modules.",
                        modNum, modulePool.size(), requiredTotal));
            }

            List<PlainMcq> shuffledPool = new ArrayList<>(modulePool);
            shuffledPool.sort(Comparator.comparingInt(PlainMcq::seq).thenComparing(PlainMcq::id));
            Random moduleRng = randomPort.createSeededRandom(seed ^ (0x9E3779B97F4A7C15L * (modNum + 1)));
            Collections.shuffle(shuffledPool, moduleRng);

            for (int c = 0; c < chunksNeeded; c++) {
                int from = c * requiredPerSet;
                int to = from + requiredPerSet;
                chunkMaps.get(c).put(modNum, List.copyOf(shuffledPool.subList(from, to)));
            }
        }

        List<GeneratedSetData> morningSets = new ArrayList<>(SETS_PER_SESSION);
        for (int s = 0; s < SETS_PER_SESSION; s++) {
            List<PlainMcq> setQuestions = assembleChunk(blueprint.selectedModules(), chunkMaps.get(s));
            morningSets.add(buildSetWithVersions(s + 1, setQuestions, seed ^ 0x1111L, randomPort));
        }

        List<GeneratedSetData> afternoonSets = List.of();
        if (includeAfternoon) {
            List<GeneratedSetData> aft = new ArrayList<>(SETS_PER_SESSION);
            for (int s = 0; s < SETS_PER_SESSION; s++) {
                List<PlainMcq> setQuestions = assembleChunk(blueprint.selectedModules(), chunkMaps.get(SETS_PER_SESSION + s));
                aft.add(buildSetWithVersions(s + 1, setQuestions, seed ^ 0x2222L, randomPort));
            }
            afternoonSets = aft;
        }

        return new DualSessionGeneration(morningSets, afternoonSets);
    }

    public List<GeneratedSetData> generateLateAfternoonSession(
            Map<Integer, List<PlainMcq>> poolByModule,
            Set<String> excludedQuestionIds,
            BlueprintResult blueprint,
            long seed,
            RandomPort randomPort
    ) {
        List<Map<Integer, List<PlainMcq>>> chunkMaps = new ArrayList<>(SETS_PER_SESSION);
        for (int s = 0; s < SETS_PER_SESSION; s++) {
            chunkMaps.add(new LinkedHashMap<>());
        }

        for (Integer modNum : blueprint.selectedModules()) {
            int requiredPerSet = blueprint.moduleCounts().getOrDefault(modNum, 0);
            List<PlainMcq> allForModule = poolByModule.getOrDefault(modNum, List.of());
            List<PlainMcq> unused = allForModule.stream()
                    .filter(q -> excludedQuestionIds == null || !excludedQuestionIds.contains(q.id()))
                    .sorted(Comparator.comparingInt(PlainMcq::seq).thenComparing(PlainMcq::id))
                    .toList();

            int needed = SETS_PER_SESSION * requiredPerSet;
            if (unused.size() < needed) {
                throw new BadRequestException(String.format(
                        Locale.ROOT,
                        "Module %d has %d unused questions after Morning session, needs %d. Add questions or pick fewer modules.",
                        modNum, unused.size(), needed));
            }

            List<PlainMcq> shuffled = new ArrayList<>(unused);
            Random moduleRng = randomPort.createSeededRandom(seed ^ (0x517CC1B727220A95L * (modNum + 3)));
            Collections.shuffle(shuffled, moduleRng);

            for (int c = 0; c < SETS_PER_SESSION; c++) {
                int from = c * requiredPerSet;
                int to = from + requiredPerSet;
                chunkMaps.get(c).put(modNum, List.copyOf(shuffled.subList(from, to)));
            }
        }

        List<GeneratedSetData> afternoonSets = new ArrayList<>(SETS_PER_SESSION);
        for (int s = 0; s < SETS_PER_SESSION; s++) {
            List<PlainMcq> setQuestions = assembleChunk(blueprint.selectedModules(), chunkMaps.get(s));
            afternoonSets.add(buildSetWithVersions(s + 1, setQuestions, seed ^ 0x3333L, randomPort));
        }
        return afternoonSets;
    }

    private List<PlainMcq> assembleChunk(List<Integer> modules, Map<Integer, List<PlainMcq>> byMod) {
        List<PlainMcq> combined = new ArrayList<>();
        for (Integer mod : modules) {
            combined.addAll(byMod.getOrDefault(mod, List.of()));
        }
        return combined;
    }

    private GeneratedSetData buildSetWithVersions(
            int setNumber,
            List<PlainMcq> baseQuestions,
            long sessionSeed,
            RandomPort randomPort
    ) {
        List<String> questionIds = baseQuestions.stream().map(PlainMcq::id).toList();
        Random setRng = randomPort.createSeededRandom(sessionSeed ^ (setNumber * 1009L));

        List<List<PlainMcq>> permutations = generateDistinctPositionPermutations(baseQuestions, VERSION_LABELS.size(), setRng);

        Map<String, GeneratedVersionData> versions = new LinkedHashMap<>();
        for (int vIdx = 0; vIdx < VERSION_LABELS.size(); vIdx++) {
            String label = VERSION_LABELS.get(vIdx);
            List<PlainMcq> ordered = permutations.get(vIdx);
            Random optRng = randomPort.createSeededRandom(sessionSeed ^ (setNumber * 1009L) ^ ((long) (vIdx + 1) * 97L));

            List<VersionQuestionSnapshot> snapshots = new ArrayList<>(ordered.size());
            List<AnswerKeyEntry> keys = new ArrayList<>(ordered.size());

            for (int pos = 0; pos < ordered.size(); pos++) {
                PlainMcq q = ordered.get(pos);
                OptionPermutation perm = permuteOptions(q.options(), q.correctIndex(), optRng);
                int oneBasedPos = pos + 1;
                snapshots.add(new VersionQuestionSnapshot(
                        oneBasedPos,
                        q.id(),
                        q.moduleNumber(),
                        q.text(),
                        perm.options()
                ));
                keys.add(new AnswerKeyEntry(
                        oneBasedPos,
                        q.id(),
                        perm.correctIndex(),
                        indexToLetter(perm.correctIndex())
                ));
            }
            versions.put(label, new GeneratedVersionData(label, Collections.unmodifiableList(snapshots), Collections.unmodifiableList(keys)));
        }

        return new GeneratedSetData(setNumber, questionIds, Collections.unmodifiableMap(versions));
    }

    private List<List<PlainMcq>> generateDistinctPositionPermutations(List<PlainMcq> base, int count, Random rng) {
        int n = base.size();
        List<List<PlainMcq>> accepted = new ArrayList<>();
        if (n == 0) {
            for (int i = 0; i < count; i++) accepted.add(List.of());
            return accepted;
        }

        if (n >= count) {
            List<Map<String, Integer>> posMaps = new ArrayList<>();
            for (int attempt = 0; attempt < 250 && accepted.size() < count; attempt++) {
                List<PlainMcq> cand = new ArrayList<>(base);
                Collections.shuffle(cand, rng);
                if (!hasPositionCollision(cand, posMaps)) {
                    Map<String, Integer> map = new HashMap<>(n * 2);
                    for (int i = 0; i < n; i++) {
                        map.put(cand.get(i).id(), i);
                    }
                    accepted.add(Collections.unmodifiableList(cand));
                    posMaps.add(map);
                }
            }
            if (accepted.size() == count) {
                return accepted;
            }
        }

        // Cyclic deterministic fallback guaranteeing zero positional collisions whenever n >= count
        accepted.clear();
        List<PlainMcq> shuffled = new ArrayList<>(base);
        Collections.shuffle(shuffled, rng);
        for (int v = 0; v < count; v++) {
            int shift = Math.max(1, n / count) * v;
            List<PlainMcq> rotated = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                rotated.add(shuffled.get((i + shift) % n));
            }
            accepted.add(Collections.unmodifiableList(rotated));
        }
        return accepted;
    }

    private boolean hasPositionCollision(List<PlainMcq> candidate, List<Map<String, Integer>> existingPosMaps) {
        for (Map<String, Integer> prevMap : existingPosMaps) {
            for (int i = 0; i < candidate.size(); i++) {
                Integer prevPos = prevMap.get(candidate.get(i).id());
                if (prevPos != null && prevPos == i) {
                    return true;
                }
            }
        }
        return false;
    }

    private OptionPermutation permuteOptions(List<String> origOptions, int origCorrectIndex, Random rng) {
        List<IndexedOption> items = new ArrayList<>(origOptions.size());
        for (int i = 0; i < origOptions.size(); i++) {
            items.add(new IndexedOption(origOptions.get(i), i == origCorrectIndex));
        }
        Collections.shuffle(items, rng);

        List<String> outOpts = new ArrayList<>(items.size());
        int newCorrect = 0;
        for (int i = 0; i < items.size(); i++) {
            IndexedOption item = items.get(i);
            outOpts.add(item.text());
            if (item.correct()) {
                newCorrect = i;
            }
        }
        return new OptionPermutation(Collections.unmodifiableList(outOpts), newCorrect);
    }

    private static String indexToLetter(int idx) {
        return String.valueOf((char) ('A' + idx));
    }

    private record IndexedOption(String text, boolean correct) {}

    private record OptionPermutation(List<String> options, int correctIndex) {}

    public record DualSessionGeneration(
            List<GeneratedSetData> morningSets,
            List<GeneratedSetData> afternoonSets
    ) {
    }
}
