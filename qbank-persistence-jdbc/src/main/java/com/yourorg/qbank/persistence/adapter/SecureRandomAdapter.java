package com.yourorg.qbank.persistence.adapter;

import com.yourorg.qbank.core.port.RandomPort;

import java.security.SecureRandom;
import java.util.Random;
import java.util.UUID;

public class SecureRandomAdapter implements RandomPort {

    private static final String ACCESS_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public long nextLong() {
        return secureRandom.nextLong();
    }

    @Override
    public Random createSeededRandom(long seed) {
        return new Random(seed);
    }

    @Override
    public String randomUuid() {
        return UUID.randomUUID().toString();
    }

    @Override
    public String generateAccessCode() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(ACCESS_ALPHABET.charAt(secureRandom.nextInt(ACCESS_ALPHABET.length())));
        }
        return sb.toString();
    }
}
