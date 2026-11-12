package com.yourorg.qbank.core.port;

import com.yourorg.qbank.core.model.AnswerKeyEntry;
import com.yourorg.qbank.core.model.FrozenPaperMeta;
import com.yourorg.qbank.core.model.FrozenVersionView;

import java.util.List;

/**
 * System-to-system exam-engine contract for reading frozen exam papers.
 * Enforces AES-GCM in-memory decryption ONLY during an active scheduled/live exam window
 * and validates stored SHA-256 content hashes before returning decrypted questions or keys.
 */
public interface FrozenPaperProvider {

    FrozenPaperMeta get(long sessionId);

    FrozenVersionView version(long sessionId, String versionLabel);

    List<AnswerKeyEntry> key(long sessionId, String versionLabel);
}
