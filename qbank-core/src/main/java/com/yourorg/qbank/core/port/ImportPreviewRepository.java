package com.yourorg.qbank.core.port;

import com.yourorg.qbank.core.model.ImportPreviewRecord;

import java.time.Instant;
import java.util.Optional;

public interface ImportPreviewRepository {
    ImportPreviewRecord save(ImportPreviewRecord record);
    Optional<ImportPreviewRecord> findById(String id);
    void deleteById(String id);
    void deleteCreatedBefore(Instant cutoff);
}
