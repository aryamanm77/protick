package com.yourorg.qbank.core.service;

import com.yourorg.qbank.core.exception.BadRequestException;
import com.yourorg.qbank.core.exception.ConflictException;
import com.yourorg.qbank.core.exception.NotFoundException;
import com.yourorg.qbank.core.model.ModuleStatus;
import com.yourorg.qbank.core.model.Subject;
import com.yourorg.qbank.core.model.SubjectModule;
import com.yourorg.qbank.core.model.UserRole;
import com.yourorg.qbank.core.port.AuditPort;
import com.yourorg.qbank.core.port.AuthPort;
import com.yourorg.qbank.core.port.ClockPort;
import com.yourorg.qbank.core.port.JsonCodecPort;
import com.yourorg.qbank.core.port.ModuleRepository;
import com.yourorg.qbank.core.port.SubjectRepository;
import com.yourorg.qbank.core.port.TransactionPort;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Manages subjects and high-level module readiness counts (never exposes question content).
 */
public class CoreSubjectService {

    private static final int MODULE_COUNT = 5;

    private final SubjectRepository subjectRepository;
    private final ModuleRepository moduleRepository;
    private final TransactionPort transactionPort;
    private final AuditPort auditPort;
    private final JsonCodecPort jsonCodec;
    private final ClockPort clockPort;
    private final AuthGuard authGuard;

    public CoreSubjectService(
            SubjectRepository subjectRepository,
            ModuleRepository moduleRepository,
            TransactionPort transactionPort,
            AuditPort auditPort,
            JsonCodecPort jsonCodec,
            ClockPort clockPort,
            AuthPort authPort
    ) {
        this.subjectRepository = Objects.requireNonNull(subjectRepository);
        this.moduleRepository = Objects.requireNonNull(moduleRepository);
        this.transactionPort = Objects.requireNonNull(transactionPort);
        this.auditPort = Objects.requireNonNull(auditPort);
        this.jsonCodec = Objects.requireNonNull(jsonCodec);
        this.clockPort = Objects.requireNonNull(clockPort);
        this.authGuard = new AuthGuard(authPort);
    }

    public Subject createSubject(String rawCode, String rawName, int expectedQuestionsPerModule, int marksPerQuestion) {
        AuthPort.AuthActor actor = authGuard.requireAnyRole(UserRole.ADMIN, UserRole.HOD, UserRole.COE);
        String code = normalizeCode(rawCode);
        String name = normalizeName(rawName);
        validateSubjectNumbers(expectedQuestionsPerModule, marksPerQuestion);

        if (subjectRepository.findByCodeIgnoreCase(code).isPresent()) {
            throw new ConflictException("Subject code already exists: " + code);
        }

        Instant now = clockPort.now();
        return transactionPort.inTransaction(() -> {
            Subject saved = subjectRepository.save(new Subject(
                    null, code, name, expectedQuestionsPerModule, marksPerQuestion, false, now
            ));
            for (int m = 1; m <= MODULE_COUNT; m++) {
                moduleRepository.save(new SubjectModule(
                        null, saved.id(), saved.code(), m, ModuleStatus.EMPTY, 0,
                        null, null, null, 0, 0
                ));
            }
            auditPort.append(
                    "SUBJECT_CREATED",
                    actor.username(),
                    AuthGuard.primaryRoleName(actor),
                    "SUBJECT",
                    saved.code(),
                    jsonCodec.toJson(Map.of(
                            "subjectId", saved.id(),
                            "expectedPerModule", expectedQuestionsPerModule,
                            "marksPerQuestion", marksPerQuestion
                    ))
            );
            return saved;
        });
    }

    public Subject updateSubject(long subjectId, String rawName, int expectedQuestionsPerModule, int marksPerQuestion, boolean archived) {
        AuthPort.AuthActor actor = authGuard.requireRole(UserRole.ADMIN);
        Subject existing = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new NotFoundException("Subject not found: " + subjectId));
        String name = normalizeName(rawName);
        validateSubjectNumbers(expectedQuestionsPerModule, marksPerQuestion);

        return transactionPort.inTransaction(() -> {
            Subject updated = subjectRepository.save(new Subject(
                    existing.id(),
                    existing.code(),
                    name,
                    expectedQuestionsPerModule,
                    marksPerQuestion,
                    archived,
                    existing.createdAt()
            ));
            auditPort.append(
                    "SUBJECT_UPDATED",
                    actor.username(),
                    AuthGuard.primaryRoleName(actor),
                    "SUBJECT",
                    updated.code(),
                    jsonCodec.toJson(Map.of(
                            "subjectId", updated.id(),
                            "expectedPerModule", expectedQuestionsPerModule,
                            "marksPerQuestion", marksPerQuestion,
                            "archived", archived
                    ))
            );
            return updated;
        });
    }

    public List<Subject> listSubjects(boolean includeArchived) {
        authGuard.requireAnyRole(UserRole.ADMIN, UserRole.HOD, UserRole.COE, UserRole.INVIGILATOR);
        return subjectRepository.findAll(includeArchived);
    }

    public Subject getSubject(long subjectId) {
        authGuard.requireAnyRole(UserRole.ADMIN, UserRole.HOD, UserRole.COE, UserRole.INVIGILATOR);
        return subjectRepository.findById(subjectId)
                .orElseThrow(() -> new NotFoundException("Subject not found: " + subjectId));
    }

    public List<SubjectModule> listModules(long subjectId) {
        authGuard.requireAnyRole(UserRole.ADMIN, UserRole.HOD, UserRole.COE, UserRole.INVIGILATOR);
        subjectRepository.findById(subjectId)
                .orElseThrow(() -> new NotFoundException("Subject not found: " + subjectId));
        return moduleRepository.findBySubjectId(subjectId).stream()
                .sorted(java.util.Comparator.comparingInt(m -> com.yourorg.qbank.core.model.ModuleUnit.sortOrder(m.moduleNumber())))
                .toList();
    }

    private static String normalizeCode(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            throw new BadRequestException("Subject code is required");
        }
        String clean = rawCode.trim().toUpperCase(Locale.ROOT);
        if (!clean.matches("[A-Z0-9_-]{2,20}")) {
            throw new BadRequestException("Subject code must be 2-20 alphanumeric/underscore/dash characters");
        }
        return clean;
    }

    private static String normalizeName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new BadRequestException("Subject name is required");
        }
        String clean = rawName.trim();
        if (clean.length() > 150) {
            throw new BadRequestException("Subject name exceeds 150 characters");
        }
        return clean;
    }

    private static void validateSubjectNumbers(int expectedQuestionsPerModule, int marksPerQuestion) {
        if (expectedQuestionsPerModule < 1 || expectedQuestionsPerModule > 2000) {
            throw new BadRequestException("Expected questions per module must be between 1 and 2000");
        }
        if (marksPerQuestion < 1 || marksPerQuestion > 10) {
            throw new BadRequestException("Marks per question must be between 1 and 10");
        }
    }
}
