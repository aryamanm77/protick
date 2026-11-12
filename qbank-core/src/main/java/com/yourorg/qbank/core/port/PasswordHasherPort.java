package com.yourorg.qbank.core.port;

public interface PasswordHasherPort {
    String hash(String rawSecret);
    boolean matches(String rawSecret, String encodedHash);
}
