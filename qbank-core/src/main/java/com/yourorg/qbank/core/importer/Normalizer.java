package com.yourorg.qbank.core.importer;

import com.yourorg.qbank.core.port.CryptoPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Normalizer {

    private static final Pattern OPTION_PREFIX =
            Pattern.compile("^\\s*(?:\\(([A-Ea-e1-5])\\)|([A-Ea-e1-5])[\\)\\.\\:\\-])\\s*");
    private static final Pattern ANSWER_LETTER =
            Pattern.compile("^(?:ans(?:wer)?\\s*[:\\-]?\\s*)?\\(?([A-Ea-e])\\)?[\\)\\.]?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANSWER_NUMBER =
            Pattern.compile("^(?:opt(?:ion)?\\s*)?\\(?([1-5])\\)?[\\)\\.]?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUESTION_NUM_PREFIX =
            Pattern.compile("^\\s*(?:Q\\.?\\s*\\d+|[0-9]{1,4})[\\)\\.\\:]\\s+(?=\\S)", Pattern.CASE_INSENSITIVE);

    /**
     * Preserves intentional line breaks inside math/code question stems while collapsing horizontal spaces.
     */
    public static String cleanWhitespace(String raw) {
        if (raw == null) {
            return "";
        }
        String unified = raw.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = unified.split("\n", -1);
        List<String> cleaned = new ArrayList<>();
        for (String line : lines) {
            String collapsed = line.replaceAll("[ \\t\\x0B\\f\\u00A0]+", " ").trim();
            if (!collapsed.isEmpty()) {
                cleaned.add(collapsed);
            }
        }
        return String.join("\n", cleaned);
    }

    public static String stripOptionPrefix(String rawOption) {
        String cleaned = cleanWhitespace(rawOption);
        if (cleaned.isEmpty()) {
            return "";
        }
        Matcher m = OPTION_PREFIX.matcher(cleaned);
        if (m.find()) {
            String stripped = cleaned.substring(m.end()).trim();
            return stripped.isEmpty() ? cleaned : stripped;
        }
        return cleaned;
    }

    public static String cleanQuestionText(String rawQuestion) {
        String cleaned = cleanWhitespace(rawQuestion);
        Matcher m = QUESTION_NUM_PREFIX.matcher(cleaned);
        if (m.find()) {
            String remainder = cleaned.substring(m.end()).trim();
            if (remainder.length() >= 8) {
                return remainder;
            }
        }
        return cleaned;
    }

    public NormalizedRow normalizeRow(
            int sourceRow,
            String rawQuestion,
            List<String> rawOptions,
            String rawAnswer,
            CryptoPort crypto
    ) {
        String cleanQ = cleanQuestionText(rawQuestion);
        List<String> cleanOpts = new ArrayList<>();
        if (rawOptions != null) {
            for (String rawOpt : rawOptions) {
                String cleanedOpt = stripOptionPrefix(rawOpt);
                if (!cleanedOpt.isEmpty()) {
                    cleanOpts.add(cleanedOpt);
                }
            }
        }

        String ansInput = cleanWhitespace(rawAnswer);
        AnswerResolution ans = resolveAnswer(ansInput, cleanOpts);
        String hashKey = cleanQ.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
        String hash = crypto.sha256Hex(hashKey);

        return new NormalizedRow(
                sourceRow,
                cleanQ,
                hash,
                cleanOpts,
                ansInput,
                ans.index,
                ans.inferredFromText
        );
    }

    public AnswerResolution resolveAnswer(String ansInput, List<String> options) {
        if (ansInput == null || ansInput.isBlank() || options == null || options.isEmpty()) {
            return new AnswerResolution(null, false);
        }

        Matcher letterMatcher = ANSWER_LETTER.matcher(ansInput);
        if (letterMatcher.matches()) {
            char ch = Character.toUpperCase(letterMatcher.group(1).charAt(0));
            int idx = ch - 'A';
            if (idx >= 0 && idx < options.size()) {
                return new AnswerResolution(idx, false);
            }
            return new AnswerResolution(null, false);
        }

        Matcher numberMatcher = ANSWER_NUMBER.matcher(ansInput);
        if (numberMatcher.matches()) {
            int idx = Integer.parseInt(numberMatcher.group(1)) - 1;
            if (idx >= 0 && idx < options.size()) {
                return new AnswerResolution(idx, false);
            }
            return new AnswerResolution(null, false);
        }

        String strippedAns = stripOptionPrefix(ansInput);
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).equalsIgnoreCase(strippedAns) || options.get(i).equalsIgnoreCase(ansInput)) {
                return new AnswerResolution(i, true);
            }
        }

        return new AnswerResolution(null, false);
    }

    public record AnswerResolution(Integer index, boolean inferredFromText) {}

    public record NormalizedRow(
            int sourceRow,
            String questionText,
            String normalizedHash,
            List<String> options,
            String rawAnswerInput,
            Integer correctIndex,
            boolean inferredFromText
    ) {
    }
}
