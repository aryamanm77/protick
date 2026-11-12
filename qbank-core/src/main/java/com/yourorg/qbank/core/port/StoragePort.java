package com.yourorg.qbank.core.port;

import com.yourorg.qbank.core.model.StoredObjectInfo;

import java.util.List;
import java.util.Optional;

/**
 * Binary storage port for Excel upload staging, session exports, and encrypted backups.
 */
public interface StoragePort {

    void store(String namespace, String key, byte[] content);

    Optional<byte[]> read(String namespace, String key);

    List<StoredObjectInfo> list(String namespace);

    void delete(String namespace, String key);

    long usableSpaceBytes(String namespace);
}
