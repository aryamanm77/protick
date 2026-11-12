package com.yourorg.qbank.persistence.adapter;

import com.yourorg.qbank.core.model.EncryptedPayload;
import com.yourorg.qbank.core.port.CryptoPort;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Production AES-256-GCM + HMAC-SHA256 adapter.
 * Keys must be supplied via external configuration or key files (never hardcoded in source).
 */
public class AesGcmCryptoAdapter implements CryptoPort {

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecretKey aesKey;
    private final SecretKey hmacKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmCryptoAdapter(byte[] rawAesKeyBytes, byte[] rawHmacKeyBytes) {
        if (rawAesKeyBytes == null || (rawAesKeyBytes.length != 16 && rawAesKeyBytes.length != 32)) {
            throw new IllegalArgumentException("AES key must be 16 or 32 bytes (256-bit recommended)");
        }
        if (rawHmacKeyBytes == null || rawHmacKeyBytes.length < 32) {
            throw new IllegalArgumentException("HMAC signing key must be at least 32 bytes");
        }
        this.aesKey = new SecretKeySpec(rawAesKeyBytes.clone(), "AES");
        this.hmacKey = new SecretKeySpec(rawHmacKeyBytes.clone(), "HmacSHA256");
    }

    @Override
    public EncryptedPayload encrypt(String plaintext, String aad) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            if (aad != null && !aad.isEmpty()) {
                cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            }
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new EncryptedPayload(
                    Base64.getEncoder().encodeToString(iv),
                    Base64.getEncoder().encodeToString(encrypted)
            );
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM encryption failure", e);
        }
    }

    @Override
    public String decrypt(EncryptedPayload payload, String aad) {
        if (payload == null) {
            throw new IllegalArgumentException("Encrypted payload must not be null");
        }
        try {
            byte[] iv = Base64.getDecoder().decode(payload.ivBase64());
            byte[] cipherBytes = Base64.getDecoder().decode(payload.cipherBase64());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            if (aad != null && !aad.isEmpty()) {
                cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            }
            byte[] plain = cipher.doFinal(cipherBytes);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM decryption or authentication tag failure", e);
        }
    }

    @Override
    public byte[] encryptBytes(byte[] plaintext, String aad) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            if (aad != null && !aad.isEmpty()) {
                cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            }
            byte[] encrypted = cipher.doFinal(plaintext);
            ByteBuffer bb = ByteBuffer.allocate(4 + GCM_IV_LENGTH + encrypted.length);
            bb.putInt(GCM_IV_LENGTH);
            bb.put(iv);
            bb.put(encrypted);
            return bb.array();
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM byte encryption failure", e);
        }
    }

    @Override
    public byte[] decryptBytes(byte[] payload, String aad) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(payload);
            int ivLen = bb.getInt();
            if (ivLen != GCM_IV_LENGTH || payload.length <= 4 + ivLen) {
                throw new IllegalArgumentException("Invalid encrypted envelope format");
            }
            byte[] iv = new byte[ivLen];
            bb.get(iv);
            byte[] encrypted = new byte[bb.remaining()];
            bb.get(encrypted);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            if (aad != null && !aad.isEmpty()) {
                cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            }
            return cipher.doFinal(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM byte decryption failure", e);
        }
    }

    @Override
    public String sha256Hex(String input) {
        return sha256BytesHex(input.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String sha256BytesHex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    public String hmacSha256Base64(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 failure", e);
        }
    }

    @Override
    public boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
