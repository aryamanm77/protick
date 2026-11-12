package com.yourorg.qbank.core.security;

/**
 * Canonical Additional Authenticated Data (AAD) builders binding AES-GCM ciphertexts
 * to their exact logical record keys to prevent cross-row ciphertext swapping.
 */
public final class QBankAad {

    private QBankAad() {
    }

    public static String mcqText(long subjectId, int moduleNumber, String mcqId) {
        return "mcq:" + subjectId + ":" + moduleNumber + ":" + mcqId + ":text";
    }

    public static String mcqOptions(long subjectId, int moduleNumber, String mcqId) {
        return "mcq:" + subjectId + ":" + moduleNumber + ":" + mcqId + ":options";
    }

    public static String mcqCorrectIndex(long subjectId, int moduleNumber, String mcqId) {
        return "mcq:" + subjectId + ":" + moduleNumber + ":" + mcqId + ":correct";
    }

    public static String previewPayload(String previewId) {
        return "preview:" + previewId;
    }

    public static String paperVersionQuestions(long paperSetId, String versionLabel) {
        return "paper-version:" + paperSetId + ":" + versionLabel + ":questions";
    }

    public static String paperVersionAnswerKey(long paperSetId, String versionLabel) {
        return "paper-version:" + paperSetId + ":" + versionLabel + ":answerKey";
    }
}
