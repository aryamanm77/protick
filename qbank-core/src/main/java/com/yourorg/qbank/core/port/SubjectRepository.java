package com.yourorg.qbank.core.port;

import com.yourorg.qbank.core.model.Subject;

import java.util.List;
import java.util.Optional;

public interface SubjectRepository {
    Subject save(Subject subject);
    Optional<Subject> findById(long id);
    Optional<Subject> findByCodeIgnoreCase(String code);
    List<Subject> findAll(boolean includeArchived);
    long countAll();
}
