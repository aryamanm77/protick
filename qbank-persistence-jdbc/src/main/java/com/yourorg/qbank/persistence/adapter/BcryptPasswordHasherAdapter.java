package com.yourorg.qbank.persistence.adapter;

import com.yourorg.qbank.core.port.PasswordHasherPort;
import org.springframework.security.crypto.bcrypt.BCrypt;

import java.security.SecureRandom;

/**
 * Pure BCrypt password/passcode hasher using Spring Security Crypto's standalone BCrypt implementation
 * without requiring Spring container or commons-logging at runtime.
 */
public class BcryptPasswordHasherAdapter implements PasswordHasherPort {

    private final int strength;
    private final SecureRandom secureRandom = new SecureRandom();

    public BcryptPasswordHasherAdapter() {
        this(10);
    }

    public BcryptPasswordHasherAdapter(int strength) {
        if (strength < 4 || strength > 31) {
            throw new IllegalArgumentException("BCrypt strength must be between 4 and 31");
        }
        this.strength = strength;
    }

    @Override
    public String hash(String rawSecret) {
        if (rawSecret == null || rawSecret.isEmpty()) {
            throw new IllegalArgumentException("Secret must not be empty");
        }
        String salt = BCrypt.gensalt("$2a", strength, secureRandom);
        return BCrypt.hashpw(rawSecret, salt);
    }

    @Override
    public boolean matches(String rawSecret, String encodedHash) {
        if (rawSecret == null || encodedHash == null || encodedHash.isBlank()) {
            return false;
        }
        try {
            return BCrypt.checkpw(rawSecret, encodedHash);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
