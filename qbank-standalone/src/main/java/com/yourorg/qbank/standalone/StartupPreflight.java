package com.yourorg.qbank.standalone;

import com.yourorg.qbank.starter.config.QBankProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Startup preflight verifying external key separation from data directories,
 * writable storage directories, and TLS keystore configuration in standalone mode.
 */
@Component
public class StartupPreflight implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(StartupPreflight.class);
    private final Environment env;
    private volatile boolean verified;

    public StartupPreflight(Environment env) {
        this.env = env;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (!verified && bean instanceof QBankProperties props) {
            runChecks(props);
            verified = true;
        }
        return bean;
    }

    public void runChecks(QBankProperties props) {
        Path storageDir = Path.of(props.getStorageDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(storageDir);
            Path probe = Files.createTempFile(storageDir, ".qbank-preflight-", ".tmp");
            Files.deleteIfExists(probe);
        } catch (IOException e) {
            throw new IllegalStateException("QBank storage directory is not writable: " + storageDir, e);
        }

        validateKeyFileOutsideStorage(props.getSecurity().getEncryptionKeyFile(), storageDir, "encryption-key-file");
        validateKeyFileOutsideStorage(props.getSecurity().getSigningKeyFile(), storageDir, "signing-key-file");

        String restoreFile = env.getProperty("qbank.restore-file", "");
        boolean restoreCliMode = restoreFile != null && !restoreFile.isBlank();
        boolean sslEnabled = Boolean.parseBoolean(env.getProperty("server.ssl.enabled", "false"));
        if (sslEnabled && !restoreCliMode) {
            String ks = env.getProperty("server.ssl.key-store", "");
            String normalizedKs = ks.startsWith("file:") ? ks.substring(5) : ks;
            if (!normalizedKs.isBlank() && !normalizedKs.startsWith("classpath:")) {
                Path ksPath = Path.of(normalizedKs).toAbsolutePath().normalize();
                if (!Files.exists(ksPath)) {
                    throw new IllegalStateException(
                            "HTTPS is enabled (server.ssl.enabled=true) but keystore was not found at: " + ksPath
                                    + ". Run scripts/generate-tls-cert before starting.");
                }
            }
        }
        log.info("QBank standalone preflight passed: storageDir={}, sslEnabled={}, restoreCliMode={}",
                storageDir, sslEnabled, restoreCliMode);
    }

    private static void validateKeyFileOutsideStorage(String keyFile, Path storageDir, String propName) {
        if (keyFile == null || keyFile.isBlank()) {
            return;
        }
        Path kp = Path.of(keyFile.trim()).toAbsolutePath().normalize();
        if (kp.startsWith(storageDir)) {
            throw new IllegalStateException(
                    "Security violation: qbank.security." + propName + " (" + kp
                            + ") must reside outside the storage/data directory (" + storageDir + ")");
        }
        if (!Files.exists(kp) || !Files.isRegularFile(kp) || !Files.isReadable(kp)) {
            throw new IllegalStateException(
                    "Missing readable key file for qbank.security." + propName + ": " + kp
                            + ". Run scripts/generate-keys before starting.");
        }
    }
}
