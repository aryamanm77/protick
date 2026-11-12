package com.yourorg.qbank.persistence.adapter;

import com.yourorg.qbank.core.model.StoredObjectInfo;
import com.yourorg.qbank.core.port.StoragePort;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Local filesystem {@link StoragePort} rejecting path traversal outside the configured base directory.
 */
public class LocalFileStorageAdapter implements StoragePort {

    private final Path baseDir;

    public LocalFileStorageAdapter(Path baseDir) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir must not be null").toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.baseDir);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create storage directory: " + this.baseDir, e);
        }
    }

    @Override
    public void store(String namespace, String key, byte[] content) {
        Path target = resolveSafe(namespace, key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write stored object " + key, e);
        }
    }

    @Override
    public Optional<byte[]> read(String namespace, String key) {
        Path target = resolveSafe(namespace, key);
        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(target));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read stored object " + key, e);
        }
    }

    @Override
    public List<StoredObjectInfo> list(String namespace) {
        Path nsDir = resolveNamespace(namespace);
        if (!Files.exists(nsDir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.list(nsDir)) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> {
                        try {
                            return new StoredObjectInfo(
                                    p.getFileName().toString(),
                                    Files.size(p),
                                    Files.getLastModifiedTime(p).toInstant()
                            );
                        } catch (IOException e) {
                            throw new IllegalStateException("Failed to inspect stored file " + p, e);
                        }
                    })
                    .sorted(Comparator.comparing(StoredObjectInfo::modifiedAt).reversed())
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list stored files in " + namespace, e);
        }
    }

    @Override
    public void delete(String namespace, String key) {
        Path target = resolveSafe(namespace, key);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete stored object " + key, e);
        }
    }

    @Override
    public long usableSpaceBytes(String namespace) {
        Path nsDir = resolveNamespace(namespace);
        try {
            Files.createDirectories(nsDir);
            return Files.getFileStore(nsDir).getUsableSpace();
        } catch (IOException e) {
            return -1L;
        }
    }

    private Path resolveNamespace(String namespace) {
        if (namespace == null || !namespace.matches("[a-zA-Z0-9_-]{1,50}")) {
            throw new IllegalArgumentException("Invalid storage namespace: " + namespace);
        }
        Path resolved = baseDir.resolve(namespace).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new SecurityException("Storage path traversal blocked");
        }
        return resolved;
    }

    private Path resolveSafe(String namespace, String key) {
        if (key == null || key.isBlank() || key.contains("..") || key.contains("/") || key.contains("\\")) {
            throw new SecurityException("Unsafe storage key rejected: " + key);
        }
        Path nsDir = resolveNamespace(namespace);
        Path target = nsDir.resolve(key).normalize();
        if (!target.startsWith(nsDir)) {
            throw new SecurityException("Storage path traversal blocked");
        }
        return target;
    }
}
