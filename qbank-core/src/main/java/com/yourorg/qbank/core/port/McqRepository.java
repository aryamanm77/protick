package com.yourorg.qbank.core.port;

import com.yourorg.qbank.core.model.EncryptedMcq;

import java.util.List;

public interface McqRepository {
    void replaceModuleMcqs(long subjectId, int moduleNumber, List<EncryptedMcq> encryptedMcqs);
    List<EncryptedMcq> findBySubjectAndModule(long subjectId, int moduleNumber);
    long countAll();
}
