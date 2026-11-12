package com.yourorg.qbank.starter.security;

import com.yourorg.qbank.core.exception.UnauthorizedException;
import com.yourorg.qbank.core.port.ClockPort;
import com.yourorg.qbank.core.port.CryptoPort;
import com.yourorg.qbank.core.port.JsonCodecPort;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Compact HMAC-SHA256 signed token codec for short-lived student exam sessions.
 * Bound strictly to sessionId, rollNumber, server-assigned version, and one-time jti.
 */
public class StudentTokenCodec {

    private static final String PREFIX = "qbs1.";
    private final CryptoPort crypto;
    private final JsonCodecPort json;
    private final ClockPort clock;

    public StudentTokenCodec(CryptoPort crypto, JsonCodecPort json, ClockPort clock) {
        this.crypto = crypto;
        this.json = json;
        this.clock = clock;
    }

    public String issueToken(StudentClaims claims) {
        String body = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.toJson(claims).getBytes(StandardCharsets.UTF_8));
        String sig = crypto.hmacSha256Base64(PREFIX + body);
        return PREFIX + body + "." + sig;
    }

    public Optional<StudentClaims> parseAndVerify(String rawToken) {
        if (rawToken == null || !rawToken.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String rest = rawToken.substring(PREFIX.length());
        int dot = rest.lastIndexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        String body = rest.substring(0, dot);
        String providedSig = rest.substring(dot + 1);
        String expectedSig = crypto.hmacSha256Base64(PREFIX + body);
        if (!crypto.constantTimeEquals(providedSig, expectedSig)) {
            return Optional.empty();
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(body);
            StudentClaims claims = json.fromJson(new String(decoded, StandardCharsets.UTF_8), StudentClaims.class);
            if (claims.expiresAt() == null || !claims.expiresAt().isAfter(clock.now())) {
                return Optional.empty();
            }
            return Optional.of(claims);
        } catch (Exception e) {
            throw new UnauthorizedException("Malformed student session token");
        }
    }

    public record StudentClaims(
            long sessionId,
            String rollNumber,
            String assignedVersion,
            String jti,
            Instant expiresAt
    ) {
    }
}
