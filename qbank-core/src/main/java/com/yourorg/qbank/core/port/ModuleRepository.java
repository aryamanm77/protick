package com.yourorg.qbank.core.port;

import com.yourorg.qbank.core.model.SubjectModule;

import java.util.List;
import java.util.Optional;

public interface ModuleRepository {
    SubjectModule save(SubjectModule module);
    Optional<SubjectModule> findBySubjectAndNumber(long subjectId, int moduleNumber);
    List<SubjectModule> findBySubjectId(long subjectId);
}
