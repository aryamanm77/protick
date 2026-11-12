package com.yourorg.qbank.core.security;

import com.yourorg.qbank.core.model.AnswerKeyEntry;
import com.yourorg.qbank.core.model.PaperVersionStored;
import com.yourorg.qbank.core.model.VersionQuestionSnapshot;
import com.yourorg.qbank.core.port.CryptoPort;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic canonical SHA-256 computation over paper versions and frozen sets
 * to detect any database ciphertext/metadata tampering prior to exam delivery or scoring.
 */
public final class PaperIntegrityCalculator {

    private PaperIntegrityCalculator() {
    }

    public static String computeVersionSha256(
            CryptoPort crypto,
            String versionLabel,
            List<VersionQuestionSnapshot> questions,
            List<AnswerKeyEntry> answerKey
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("V=").append(Objects.requireNonNullElse(versionLabel, "")).append('\n');
        for (VersionQuestionSnapshot q : questions) {
            sb.append("Q|").append(q.position())
                    .append('|').append(q.mcqId())
                    .append('|').append(q.moduleNumber())
                    .append('|').append(q.text());
            for (String opt : q.options()) {
                sb.append('|').append(opt);
            }
            sb.append('\n');
        }
        for (AnswerKeyEntry k : answerKey) {
            sb.append("K|").append(k.position())
                    .append('|').append(k.mcqId())
                    .append('|').append(k.correctIndex())
                    .append('|').append(k.correctLetter())
                    .append('\n');
        }
        return crypto.sha256Hex(sb.toString());
    }

    public static String computeSessionSetSha256(CryptoPort crypto, int setNumber, List<PaperVersionStored> versions) {
        List<PaperVersionStored> sorted = versions.stream()
                .sorted(Comparator.comparing(PaperVersionStored::versionLabel))
                .toList();
        StringBuilder sb = new StringBuilder();
        sb.append("SET=").append(setNumber).append('\n');
        for (PaperVersionStored v : sorted) {
            sb.append(v.versionLabel()).append(':').append(v.contentSha256()).append('\n');
        }
        return crypto.sha256Hex(sb.toString());
    }
}
