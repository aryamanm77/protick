package com.yourorg.qbank.core.port;

import java.util.Random;

/**
 * Randomness and UUID generation port supporting deterministic test implementations.
 */
public interface RandomPort {

    long nextLong();

    Random createSeededRandom(long seed);

    String randomUuid();

    /**
     * Generates a high-entropy single-use student session passcode.
     */
    String generateAccessCode();
}
