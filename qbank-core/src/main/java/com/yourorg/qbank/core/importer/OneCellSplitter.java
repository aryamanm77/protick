package com.yourorg.qbank.core.importer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OneCellSplitter {

    private static final Pattern LETTER_MARKER =
            Pattern.compile("(?:^|[\\s\\r\\n])(?:\\(([A-Ea-e])\\)|([A-Ea-e])[\\)\\.\\:])\\s+");
    private static final Pattern NUMBER_MARKER =
            Pattern.compile("(?:^|[\\s\\r\\n])(?:\\(([1-5])\\)|([1-5])[\\)\\.])\\s+");
    private static final Pattern TRAILING_ANSWER =
            Pattern.compile("(?is)\\s+(?:ans(?:wer)?|key)\\s*[:\\-]\\s*([A-Ea-e1-5])\\.?\\s*$");

    public static boolean containsPackedOptions(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return findSequentialMarkers(text, LETTER_MARKER, new char[]{'A', 'B', 'C', 'D', 'E'}).size() >= 2
                || findSequentialMarkers(text, NUMBER_MARKER, new char[]{'1', '2', '3', '4', '5'}).size() >= 2;
    }

    public SplitResult split(String cellText) {
        if (cellText == null || cellText.isBlank()) {
            return new SplitResult("", List.of(), null, false);
        }

        List<MarkerSpan> markers = findSequentialMarkers(cellText, LETTER_MARKER, new char[]{'A', 'B', 'C', 'D', 'E'});
        if (markers.size() < 2) {
            markers = findSequentialMarkers(cellText, NUMBER_MARKER, new char[]{'1', '2', '3', '4', '5'});
        }
        if (markers.size() < 2) {
            return new SplitResult(cellText.trim(), List.of(), null, false);
        }

        String question = cellText.substring(0, markers.get(0).startOffset).trim();
        List<String> options = new ArrayList<>();
        for (int i = 0; i < markers.size(); i++) {
            int contentStart = markers.get(i).endOffset;
            int contentEnd = (i + 1 < markers.size()) ? markers.get(i + 1).startOffset : cellText.length();
            options.add(cellText.substring(contentStart, contentEnd).trim());
        }

        String extractedAnswer = null;
        if (!options.isEmpty()) {
            int lastIdx = options.size() - 1;
            String lastOpt = options.get(lastIdx);
            Matcher ansMatcher = TRAILING_ANSWER.matcher(lastOpt);
            if (ansMatcher.find()) {
                extractedAnswer = ansMatcher.group(1).trim();
                options.set(lastIdx, lastOpt.substring(0, ansMatcher.start()).trim());
            }
        }

        return new SplitResult(question, options, extractedAnswer, true);
    }

    private static List<MarkerSpan> findSequentialMarkers(String text, Pattern pattern, char[] expectedSequence) {
        Matcher matcher = pattern.matcher(text);
        List<MarkerSpan> found = new ArrayList<>();
        int expectedIdx = 0;

        while (matcher.find() && expectedIdx < expectedSequence.length) {
            String rawLabel = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            char label = Character.toUpperCase(rawLabel.charAt(0));
            if (label == expectedSequence[expectedIdx]) {
                found.add(new MarkerSpan(matcher.start(), matcher.end()));
                expectedIdx++;
            }
        }
        return found.size() >= 2 ? found : List.of();
    }

    private record MarkerSpan(int startOffset, int endOffset) {}

    public record SplitResult(
            String questionText,
            List<String> options,
            String extractedAnswer,
            boolean wasSplit
    ) {
    }
}
