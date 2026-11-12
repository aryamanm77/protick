package com.yourorg.qbank.standalone;

import com.yourorg.qbank.starter.config.QBankProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupPreflightTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsKeyFilesPlacedInsideDataStorageDirectory() throws Exception {
        Path storage = tempDir.resolve("data/storage");
        Files.createDirectories(storage);
        Path keyInsideStorage = storage.resolve("aes.key");
        Files.writeString(keyInsideStorage, "AAAA");

        QBankProperties props = new QBankProperties();
        props.setStorageDir(storage.toString());
        props.getSecurity().setEncryptionKeyFile(keyInsideStorage.toString());

        StartupPreflight preflight = new StartupPreflight(new MockEnvironment());
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> preflight.runChecks(props));
        assertTrue(ex.getMessage().contains("outside the storage/data directory"));
    }

    @Test
    void rejectsMissingTlsKeystoreWhenSslEnabled() throws Exception {
        Path storage = tempDir.resolve("data/storage");
        Path secrets = tempDir.resolve("secrets");
        Files.createDirectories(storage);
        Files.createDirectories(secrets);
        Path aes = secrets.resolve("aes.key");
        Path hmac = secrets.resolve("hmac.key");
        Files.writeString(aes, "AAAA");
        Files.writeString(hmac, "BBBB");

        QBankProperties props = new QBankProperties();
        props.setStorageDir(storage.toString());
        props.getSecurity().setEncryptionKeyFile(aes.toString());
        props.getSecurity().setSigningKeyFile(hmac.toString());

        MockEnvironment env = new MockEnvironment()
                .withProperty("server.ssl.enabled", "true")
                .withProperty("server.ssl.key-store", tempDir.resolve("missing.p12").toString());

        StartupPreflight preflight = new StartupPreflight(env);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> preflight.runChecks(props));
        assertTrue(ex.getMessage().contains("keystore was not found"));

        MockEnvironment okEnv = new MockEnvironment().withProperty("server.ssl.enabled", "false");
        assertDoesNotThrow(() -> new StartupPreflight(okEnv).runChecks(props));
    }
}
