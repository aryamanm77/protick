package com.yourorg.qbank.starter.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Resolves 256-bit AES and HMAC-SHA256 key material exclusively from external configuration,
 * key files outside the data directory, or environment variables (zero hardcoded keys in source).
 */
public final class KeyMaterialResolver {

    private KeyMaterialResolver() {
    }

    public static byte[] resolveKeyBytes(
            String configBase64,
            String keyFilePath,
            String envVarName,
            String description,
            int requiredMinBytes
    ) {
        String b64 = firstNonBlank(configBase64, System.getenv(envVarName));
        if (b64 == null && keyFilePath != null && !keyFilePath.isBlank()) {
            Path p = Path.of(keyFilePath.trim()).toAbsolutePath().normalize();
            if (!Files.exists(p) || !Files.isRegularFile(p)) {
                throw new IllegalStateException(description + " file not found at: " + p
                        + ". Generate external keys via scripts/generate-keys before starting.");
            }
            try {
                b64 = Files.readString(p, StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read " + description + " file: " + p, e);
            }
        }
        if (b64 == null) {
            throw new IllegalStateException(
                    "Missing " + description + ". Set config property, key file outside data/, or env " + envVarName + ".");
        }
        byte[] decoded = Base64.getDecoder().decode(b64.trim());
        if (decoded.length < requiredMinBytes) {
            throw new IllegalStateException(description + " decoded length is " + decoded.length
                    + " bytes, requires at least " + requiredMinBytes + " bytes");
        }
        return decoded;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return null;
    }
}
