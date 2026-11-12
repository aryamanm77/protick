package com.yourorg.qbank.core.port;

import com.yourorg.qbank.core.model.EncryptedPayload;

/**
 * Symmetric AES-GCM at-rest encryption and cryptographic hashing port.
 */
public interface CryptoPort {

    EncryptedPayload encrypt(String plaintext, String aad);

    String decrypt(EncryptedPayload payload, String aad);

    byte[] encryptBytes(byte[] plaintext, String aad);

    byte[] decryptBytes(byte[] payload, String aad);

    String sha256Hex(String input);

    String sha256BytesHex(byte[] data);

    String hmacSha256Base64(String data);

    boolean constantTimeEquals(String a, String b);
}
