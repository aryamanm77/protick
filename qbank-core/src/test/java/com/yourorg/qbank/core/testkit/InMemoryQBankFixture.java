package com.yourorg.qbank.core.testkit;

import com.yourorg.qbank.core.event.QBankEvent;
import com.yourorg.qbank.core.importer.RawRow;
import com.yourorg.qbank.core.importer.RawSheetData;
import com.yourorg.qbank.core.model.*;
import com.yourorg.qbank.core.port.*;
import com.yourorg.qbank.core.security.AuditChainCalculator;
import com.yourorg.qbank.core.service.*;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Pure Java in-memory fixture exercising all qbank-core services and ports without Spring or JDBC.
 */
public class InMemoryQBankFixture {

    public final MutableClock clock = new MutableClock(Instant.parse("2026-11-11T09:00:00Z"));
    public final SwitchableAuthPort auth = new SwitchableAuthPort();
    public final FakeRandomPort random = new FakeRandomPort();
    public final RealAesCryptoPort crypto = new RealAesCryptoPort();
    public final InMemoryJsonCodec json = new InMemoryJsonCodec();
    public final FakePasswordHasher hasher = new FakePasswordHasher();
    public final TransactionPort tx = new TransactionPort() {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    };
    public final List<QBankEvent> publishedEvents = new ArrayList<>();
    public final EventPublisher events = publishedEvents::add;

    public final InMemorySubjectRepo subjectRepo = new InMemorySubjectRepo();
    public final InMemoryModuleRepo moduleRepo = new InMemoryModuleRepo();
    public final InMemoryMcqRepo mcqRepo = new InMemoryMcqRepo();
    public final InMemoryExamRepo examRepo = new InMemoryExamRepo();
    public final InMemoryPreviewRepo previewRepo = new InMemoryPreviewRepo();
    public final InMemoryStudentRepo studentRepo = new InMemoryStudentRepo();
    public final InMemoryResponseRepo responseRepo = new InMemoryResponseRepo();
    public final InMemoryGrantRepo grantRepo = new InMemoryGrantRepo();
    public final InMemoryUserRepo userRepo = new InMemoryUserRepo();
    public final InMemoryUserSessionRepo userSessionRepo = new InMemoryUserSessionRepo();
    public final InMemoryAuditPort auditPort = new InMemoryAuditPort(crypto, clock);
    public final FakeSpreadsheetPort spreadsheetPort = new FakeSpreadsheetPort();

    public final CoreSubjectService subjectService = new CoreSubjectService(
            subjectRepo, moduleRepo, tx, auditPort, json, clock, auth);
    public final CoreImportService importService = new CoreImportService(
            subjectRepo, moduleRepo, mcqRepo, examRepo, previewRepo,
            spreadsheetPort, crypto, json, random, clock, tx, auditPort, events, auth);
    public final DefaultFrozenPaperProvider frozenPaperProvider = new DefaultFrozenPaperProvider(
            examRepo, studentRepo, crypto, json, clock, auditPort);
    public final CoreExamPaperService examPaperService = new CoreExamPaperService(
            subjectRepo, moduleRepo, mcqRepo, examRepo,
            crypto, json, random, clock, tx, auditPort, events, auth, frozenPaperProvider);
    public final CoreStudentRosterAndInvigilatorService rosterService = new CoreStudentRosterAndInvigilatorService(
            examRepo, studentRepo, responseRepo, grantRepo, hasher, random, clock, tx, auditPort, json, auth);
    public final ResponseSink responseSink = responseRepo::upsertResponse;
    public final CoreStudentExamService studentExamService = new CoreStudentExamService(
            examRepo, studentRepo, responseRepo, frozenPaperProvider, responseSink,
            hasher, crypto, random, clock, tx, auditPort, json, auth);
    public final CoreResultsExportService resultsExportService = new CoreResultsExportService(
            examRepo, studentRepo, responseRepo, frozenPaperProvider, spreadsheetPort, crypto, json, auditPort, auth);
    public final CoreStandaloneAuthService authService = new CoreStandaloneAuthService(
            userRepo, userSessionRepo, hasher, crypto, random, clock, tx, auditPort, json, auth);
    public final CoreAuditService auditService = new CoreAuditService(auditPort, json, auth);

    public static List<RawSheetData> makeStandardModuleSheets(int modules, int questionsPerModule) {
        List<RawSheetData> sheets = new ArrayList<>();
        for (int m = 1; m <= modules; m++) {
            sheets.add(makeSingleModuleSheet("Module " + m, m, questionsPerModule));
        }
        return sheets;
    }

    public static RawSheetData makeSingleModuleSheet(String sheetName, int mod, int count) {
        List<RawRow> rows = new ArrayList<>();
        rows.add(new RawRow(1, List.of("Question", "Option A", "Option B", "Option C", "Option D", "Correct")));
        String[] letters = {"A", "B", "C", "D"};
        for (int i = 1; i <= count; i++) {
            int correctIdx = (i - 1) % 4;
            rows.add(new RawRow(i + 1, List.of(
                    "M" + mod + " Question #" + i + " stem text",
                    "M" + mod + "-Q" + i + "-Alpha",
                    "M" + mod + "-Q" + i + "-Beta",
                    "M" + mod + "-Q" + i + "-Gamma",
                    "M" + mod + "-Q" + i + "-Delta",
                    letters[correctIdx]
            )));
        }
        return new RawSheetData(sheetName, 6, rows, Set.of());
    }

    public void asAdmin(String username) {
        auth.setActor(new AuthPort.AuthActor(username, "Admin User", Set.of(UserRole.ADMIN)));
    }

    public void asHod(String username) {
        auth.setActor(new AuthPort.AuthActor(username, "HOD User", Set.of(UserRole.HOD)));
    }

    public void asCoe(String username) {
        auth.setActor(new AuthPort.AuthActor(username, "COE User", Set.of(UserRole.COE)));
    }

    public void asInvigilator(String username, String roomCode) {
        auth.setActor(new AuthPort.AuthActor(username, "Invigilator", Set.of(UserRole.INVIGILATOR), null, null, roomCode));
    }

    public void asStudent(String rollNumber, long sessionId, String version) {
        auth.setActor(new AuthPort.AuthActor(rollNumber, rollNumber, Set.of(UserRole.STUDENT), sessionId, version, null));
    }

    public void clearAuth() {
        auth.setActor(null);
    }

    public static class MutableClock implements ClockPort {
        private Instant current;
        public MutableClock(Instant start) { this.current = start; }
        @Override public Instant now() { return current; }
        public void set(Instant t) { this.current = t; }
        public void advanceSeconds(long s) { this.current = this.current.plusSeconds(s); }
    }

    public static class SwitchableAuthPort implements AuthPort {
        private AuthActor actor;
        public void setActor(AuthActor actor) { this.actor = actor; }
        @Override public Optional<AuthActor> currentActor() { return Optional.ofNullable(actor); }
    }

    public static class FakeRandomPort implements RandomPort {
        private final AtomicLong seq = new AtomicLong(42L);
        @Override public long nextLong() { return seq.addAndGet(0x9E3779B97F4A7C15L); }
        @Override public Random createSeededRandom(long seed) { return new Random(seed); }
        @Override public String randomUuid() { return "uuid-" + seq.incrementAndGet(); }
        @Override public String generateAccessCode() { return "CODE" + (100000 + (Math.abs(seq.incrementAndGet()) % 900000)); }
    }

    public static class FakePasswordHasher implements PasswordHasherPort {
        @Override public String hash(String rawSecret) { return "BCRYPT$" + rawSecret; }
        @Override public boolean matches(String rawSecret, String encodedHash) {
            return rawSecret != null && ("BCRYPT$" + rawSecret).equals(encodedHash);
        }
    }

    public static class RealAesCryptoPort implements CryptoPort {
        private final SecretKeySpec keySpec;
        private final SecureRandom secureRandom = new SecureRandom();

        public RealAesCryptoPort() {
            byte[] key = new byte[32];
            for (int i = 0; i < 32; i++) key[i] = (byte) (i + 17);
            this.keySpec = new SecretKeySpec(key, "AES");
        }

        @Override
        public EncryptedPayload encrypt(String plaintext, String aad) {
            try {
                byte[] iv = new byte[12];
                secureRandom.nextBytes(iv);
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
                if (aad != null) c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
                byte[] out = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
                return new EncryptedPayload(
                        Base64.getEncoder().encodeToString(iv),
                        Base64.getEncoder().encodeToString(out));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public String decrypt(EncryptedPayload payload, String aad) {
            try {
                byte[] iv = Base64.getDecoder().decode(payload.ivBase64());
                byte[] cipher = Base64.getDecoder().decode(payload.cipherBase64());
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
                if (aad != null) c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
                return new String(c.doFinal(cipher), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException("Decryption failed", e);
            }
        }

        @Override public byte[] encryptBytes(byte[] plaintext, String aad) { return plaintext; }
        @Override public byte[] decryptBytes(byte[] payload, String aad) { return payload; }

        @Override
        public String sha256Hex(String input) {
            return sha256BytesHex(input.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String sha256BytesHex(byte[] data) {
            try {
                byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
                StringBuilder sb = new StringBuilder(64);
                for (byte b : d) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public String hmacSha256Base64(String data) {
            return Base64.getEncoder().encodeToString(sha256Hex(data).getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public boolean constantTimeEquals(String a, String b) {
            if (a == null || b == null) return false;
            return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static class InMemoryJsonCodec implements JsonCodecPort {
        private final ConcurrentHashMap<String, Object> store = new ConcurrentHashMap<>();
        private final AtomicLong refSeq = new AtomicLong();

        @Override
        public String toJson(Object value) {
            if (value instanceof Map<?, ?> m) {
                return m.entrySet().stream()
                        .sorted(Comparator.comparing(e -> String.valueOf(e.getKey())))
                        .map(e -> "\"" + e.getKey() + "\":\"" + e.getValue() + "\"")
                        .collect(Collectors.joining(",", "{", "}"));
            }
            String key = "@ref:" + refSeq.incrementAndGet();
            store.put(key, deepFreeze(value));
            return key;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T fromJson(String json, Class<T> type) {
            return (T) store.get(json);
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> List<T> fromJsonList(String json, Class<T> elementType) {
            return (List<T>) store.get(json);
        }

        @Override public Map<Integer, Integer> fromJsonIntMap(String json) { return Map.of(); }
        @Override public List<Integer> fromJsonIntList(String json) { return List.of(); }

        @SuppressWarnings("unchecked")
        @Override
        public List<String> fromJsonStringList(String json) {
            return (List<String>) store.get(json);
        }

        private Object deepFreeze(Object v) {
            if (v instanceof List<?> l) return List.copyOf(l);
            return v;
        }
    }

    public static class FakeSpreadsheetPort implements SpreadsheetPort {
        private final Map<String, List<RawSheetData>> stagedFiles = new ConcurrentHashMap<>();
        public List<List<String>> lastExportedRows = List.of();

        public byte[] stageWorkbook(String filename, List<RawSheetData> sheets) {
            stagedFiles.put(filename, sheets);
            return filename.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public List<RawSheetData> readWorkbook(byte[] content, String filename, int maxBytes) {
            if (content.length > maxBytes) throw new IllegalArgumentException("File exceeds limit");
            return stagedFiles.getOrDefault(filename, List.of());
        }

        @Override public byte[] createBlankTemplate(boolean multiModule) { return new byte[]{1, 2, 3}; }

        @Override
        public byte[] createSanitizedWorkbook(String sheetName, List<List<String>> rows) {
            this.lastExportedRows = rows;
            return ("XLSX:" + rows.size()).getBytes(StandardCharsets.UTF_8);
        }
    }

    public static class InMemorySubjectRepo implements SubjectRepository {
        private final AtomicLong seq = new AtomicLong();
        private final Map<Long, Subject> byId = new LinkedHashMap<>();

        @Override
        public Subject save(Subject s) {
            long id = s.id() != null ? s.id() : seq.incrementAndGet();
            Subject stored = new Subject(id, s.code(), s.name(), s.expectedQuestionsPerModule(), s.marksPerQuestion(), s.archived(), s.createdAt());
            byId.put(id, stored);
            return stored;
        }

        @Override public Optional<Subject> findById(long id) { return Optional.ofNullable(byId.get(id)); }
        @Override public Optional<Subject> findByCodeIgnoreCase(String code) {
            return byId.values().stream().filter(s -> s.code().equalsIgnoreCase(code)).findFirst();
        }
        @Override public List<Subject> findAll(boolean includeArchived) {
            return byId.values().stream().filter(s -> includeArchived || !s.archived()).toList();
        }
        @Override public long countAll() { return byId.size(); }
    }

    public static class InMemoryModuleRepo implements ModuleRepository {
        private final AtomicLong seq = new AtomicLong();
        private final Map<String, SubjectModule> byKey = new LinkedHashMap<>();

        @Override
        public SubjectModule save(SubjectModule m) {
            long id = m.id() != null ? m.id() : seq.incrementAndGet();
            SubjectModule stored = new SubjectModule(id, m.subjectId(), m.subjectCode(), m.moduleNumber(),
                    m.status(), m.questionCount(), m.lastImportFilename(), m.lastImportedBy(),
                    m.lastImportedAt(), m.lastErrorCount(), m.lastWarningCount());
            byKey.put(m.subjectId() + ":" + m.moduleNumber(), stored);
            return stored;
        }

        @Override public Optional<SubjectModule> findBySubjectAndNumber(long subjectId, int moduleNumber) {
            return Optional.ofNullable(byKey.get(subjectId + ":" + moduleNumber));
        }

        @Override public List<SubjectModule> findBySubjectId(long subjectId) {
            return byKey.values().stream()
                    .filter(m -> Objects.equals(m.subjectId(), subjectId))
                    .sorted(Comparator.comparingInt(SubjectModule::moduleNumber))
                    .toList();
        }
    }

    public static class InMemoryMcqRepo implements McqRepository {
        public final Map<String, List<EncryptedMcq>> byMod = new ConcurrentHashMap<>();

        @Override
        public void replaceModuleMcqs(long subjectId, int moduleNumber, List<EncryptedMcq> encryptedMcqs) {
            byMod.put(subjectId + ":" + moduleNumber, new ArrayList<>(encryptedMcqs));
        }

        @Override
        public List<EncryptedMcq> findBySubjectAndModule(long subjectId, int moduleNumber) {
            return byMod.getOrDefault(subjectId + ":" + moduleNumber, List.of());
        }

        @Override public long countAll() { return byMod.values().stream().mapToLong(List::size).sum(); }
    }

    public static class InMemoryExamRepo implements ExamRepository {
        private final AtomicLong examSeq = new AtomicLong();
        private final AtomicLong sessionSeq = new AtomicLong();
        private final AtomicLong setSeq = new AtomicLong();
        private final AtomicLong verSeq = new AtomicLong();
        private final Map<Long, Exam> exams = new LinkedHashMap<>();
        private final Map<Long, ExamSession> sessions = new LinkedHashMap<>();
        private final Map<Long, PaperSetStored> sets = new LinkedHashMap<>();
        private final Map<Long, PaperVersionStored> versions = new LinkedHashMap<>();

        @Override public Exam saveExam(Exam e) {
            long id = e.id() != null ? e.id() : examSeq.incrementAndGet();
            Exam s = new Exam(id, e.subjectId(), e.subjectCode(), e.subjectName(), e.title(), e.examType(),
                    e.examDate(), e.marksPerQuestion(), e.totalMarks(), e.totalQuestions(),
                    e.selectedModules(), e.blueprint(), e.rotationUsed(), e.rngSeed(), e.createdBy(), e.createdAt());
            exams.put(id, s);
            return s;
        }
        @Override public Optional<Exam> findExamById(long id) { return Optional.ofNullable(exams.get(id)); }
        @Override public List<Exam> findAllExams() { return List.copyOf(exams.values()); }
        @Override public List<Exam> findExamsBySubjectId(long sid) {
            return exams.values().stream().filter(e -> Objects.equals(e.subjectId(), sid)).toList();
        }
        @Override public long countExams() { return exams.size(); }

        @Override public ExamSession saveSession(ExamSession s) {
            long id = s.id() != null ? s.id() : sessionSeq.incrementAndGet();
            ExamSession stored = new ExamSession(id, s.examId(), s.sessionSlot(), s.status(), s.chosenSetNumber(),
                    s.scheduledStartAt(), s.scheduledEndAt(), s.actualStartedAt(), s.actualEndedAt(),
                    s.startedBy(), s.endedBy(), s.frozenBy(), s.frozenAt(), s.contentSha256(), s.version());
            sessions.put(id, stored);
            return stored;
        }
        @Override public Optional<ExamSession> findSessionById(long id) { return Optional.ofNullable(sessions.get(id)); }
        @Override public List<ExamSession> findSessionsByExamId(long eid) {
            return sessions.values().stream().filter(s -> Objects.equals(s.examId(), eid)).toList();
        }
        @Override public List<ExamSession> findAllSessions() { return List.copyOf(sessions.values()); }
        @Override public List<ExamSession> findFrozenOrLiveSessionsUsingModule(long subjectId, int moduleNumber) {
            return sessions.values().stream()
                    .filter(s -> s.status() == SessionStatus.FROZEN || s.status() == SessionStatus.LIVE)
                    .filter(s -> {
                        Exam e = exams.get(s.examId());
                        return e != null && Objects.equals(e.subjectId(), subjectId)
                                && e.selectedModules().stream().anyMatch(m -> ModuleUnit.overlaps(m, moduleNumber));
                    })
                    .toList();
        }

        @Override public PaperSetStored savePaperSet(PaperSetStored ps) {
            long id = ps.id() != null ? ps.id() : setSeq.incrementAndGet();
            PaperSetStored stored = new PaperSetStored(id, ps.sessionId(), ps.setNumber(), ps.status(), ps.questionCount(), ps.questionIds());
            sets.put(id, stored);
            return stored;
        }
        @Override public List<PaperSetStored> findSetsBySessionId(long sid) {
            return sets.values().stream().filter(s -> Objects.equals(s.sessionId(), sid))
                    .sorted(Comparator.comparingInt(PaperSetStored::setNumber)).toList();
        }
        @Override public void updatePaperSetStatus(long setId, PaperSetStatus status) {
            PaperSetStored cur = sets.get(setId);
            if (cur != null) sets.put(setId, new PaperSetStored(cur.id(), cur.sessionId(), cur.setNumber(), status, cur.questionCount(), cur.questionIds()));
        }
        @Override public Set<String> findQuestionIdsUsedInSession(long sid) {
            Set<String> all = new LinkedHashSet<>();
            for (PaperSetStored s : findSetsBySessionId(sid)) all.addAll(s.questionIds());
            return all;
        }

        @Override public void savePaperVersion(PaperVersionStored v) {
            long id = v.id() != null ? v.id() : verSeq.incrementAndGet();
            versions.put(id, new PaperVersionStored(id, v.paperSetId(), v.versionLabel(), v.questionsEnc(), v.answerKeyEnc(), v.contentSha256()));
        }

        /** Expose raw version map strictly for security tamper simulation tests. */
        public void tamperVersionHash(long paperSetId, String label, String badSha) {
            versions.replaceAll((id, v) -> Objects.equals(v.paperSetId(), paperSetId) && label.equals(v.versionLabel())
                    ? new PaperVersionStored(v.id(), v.paperSetId(), v.versionLabel(), v.questionsEnc(), v.answerKeyEnc(), badSha) : v);
        }

        @Override public Optional<PaperVersionStored> findSelectedVersion(long sid, String label) {
            ExamSession s = sessions.get(sid);
            if (s == null || s.chosenSetNumber() == null) return Optional.empty();
            return sets.values().stream()
                    .filter(ps -> Objects.equals(ps.sessionId(), sid) && ps.setNumber() == s.chosenSetNumber())
                    .findFirst()
                    .flatMap(ps -> findVersionsBySetId(ps.id()).stream().filter(v -> label.equals(v.versionLabel())).findFirst());
        }
        @Override public List<PaperVersionStored> findVersionsBySetId(long setId) {
            return versions.values().stream().filter(v -> Objects.equals(v.paperSetId(), setId)).toList();
        }

        @Override
        public synchronized boolean updateSessionStatusWithVersion(
                long sessionId, int expectedVersion, SessionStatus newStatus, Integer chosenSetNumber,
                Instant actualStartedAt, Instant actualEndedAt, String startedBy, String endedBy,
                String frozenBy, Instant frozenAt, String contentSha256) {
            ExamSession cur = sessions.get(sessionId);
            if (cur == null || cur.version() != expectedVersion) return false;
            sessions.put(sessionId, new ExamSession(
                    cur.id(), cur.examId(), cur.sessionSlot(), newStatus, chosenSetNumber,
                    cur.scheduledStartAt(), cur.scheduledEndAt(), actualStartedAt, actualEndedAt,
                    startedBy, endedBy, frozenBy, frozenAt, contentSha256, expectedVersion + 1));
            return true;
        }
    }

    public static class InMemoryPreviewRepo implements ImportPreviewRepository {
        private final Map<String, ImportPreviewRecord> map = new ConcurrentHashMap<>();
        @Override public ImportPreviewRecord save(ImportPreviewRecord r) { map.put(r.id(), r); return r; }
        @Override public Optional<ImportPreviewRecord> findById(String id) { return Optional.ofNullable(map.get(id)); }
        @Override public void deleteById(String id) { map.remove(id); }
        @Override public void deleteCreatedBefore(Instant cutoff) {
            map.values().removeIf(r -> r.createdAt().isBefore(cutoff));
        }
    }

    public static class InMemoryStudentRepo implements ExamStudentRepository {
        private final AtomicLong seq = new AtomicLong();
        private final Map<String, ExamStudent> map = new LinkedHashMap<>();

        @Override public ExamStudent save(ExamStudent s) {
            long id = s.id() != null ? s.id() : seq.incrementAndGet();
            ExamStudent stored = new ExamStudent(id, s.sessionId(), s.rollNumber(), s.fullName(), s.roomCode(),
                    s.rowNumber(), s.seatNumber(), s.assignedVersion(), s.accessCodeHash(), s.loginState(),
                    s.activeTokenJti(), s.fontScale(), s.highContrast(), s.accommodationExtraMinutes(),
                    s.grantedExtraMinutes(), s.lastQuestionIndex(), s.lastHeartbeatAt(), s.finishedAt(), s.version() + 1);
            map.put(s.sessionId() + ":" + s.rollNumber(), stored);
            return stored;
        }
        @Override public Optional<ExamStudent> findBySessionAndRoll(long sid, String roll) {
            return Optional.ofNullable(map.get(sid + ":" + roll));
        }
        @Override public List<ExamStudent> findBySessionId(long sid) {
            return map.values().stream().filter(s -> s.sessionId() == sid).toList();
        }
        @Override public List<ExamStudent> findBySessionAndRoom(long sid, String room) {
            return map.values().stream().filter(s -> s.sessionId() == sid && room.equalsIgnoreCase(s.roomCode())).toList();
        }
        @Override public int maxExtraTimeForSession(long sid) {
            return findBySessionId(sid).stream().mapToInt(s -> s.accommodationExtraMinutes() + s.grantedExtraMinutes()).max().orElse(0);
        }
        @Override public long countAll() { return map.size(); }
    }

    public static class InMemoryResponseRepo implements StudentResponseRepository {
        private final AtomicLong seq = new AtomicLong();
        private final Map<String, StudentResponseRecord> map = new ConcurrentHashMap<>();

        @Override public StudentResponseRecord upsertResponse(StudentResponseRecord r) {
            String key = r.sessionId() + ":" + r.rollNumber() + ":" + r.questionPosition();
            long id = map.containsKey(key) ? map.get(key).id() : seq.incrementAndGet();
            StudentResponseRecord stored = new StudentResponseRecord(
                    id, r.sessionId(), r.rollNumber(), r.questionPosition(), r.selectedOptionIndex(),
                    r.versionLabel(), r.clientTimestamp(), r.serverReceivedAt());
            map.put(key, stored);
            return stored;
        }
        @Override public List<StudentResponseRecord> findBySessionAndRoll(long sid, String roll) {
            return map.values().stream().filter(r -> r.sessionId() == sid && roll.equals(r.rollNumber())).toList();
        }
        @Override public List<StudentResponseRecord> findBySessionId(long sid) {
            return map.values().stream().filter(r -> r.sessionId() == sid).toList();
        }
        @Override public long countAll() { return map.size(); }
        @Override public int deleteResponsesForEndedSessionsBefore(Instant cutoff, String actor, String reason) { return 0; }
    }

    public static class InMemoryGrantRepo implements ExtraTimeGrantRepository {
        private final AtomicLong seq = new AtomicLong();
        private final List<ExtraTimeGrant> grants = new ArrayList<>();
        @Override public ExtraTimeGrant save(ExtraTimeGrant g) {
            ExtraTimeGrant s = new ExtraTimeGrant(seq.incrementAndGet(), g.sessionId(), g.roomCode(),
                    g.rollNumber(), g.extraMinutes(), g.reason(), g.grantedBy(), g.grantedAt());
            grants.add(s);
            return s;
        }
        @Override public List<ExtraTimeGrant> findBySessionId(long sid) {
            return grants.stream().filter(g -> g.sessionId() == sid).toList();
        }
    }

    public static class InMemoryUserRepo implements UserAccountRepository {
        private final AtomicLong seq = new AtomicLong();
        private final Map<Long, UserAccount> byId = new LinkedHashMap<>();
        @Override public UserAccount save(UserAccount u) {
            long id = u.id() != null ? u.id() : seq.incrementAndGet();
            UserAccount s = new UserAccount(id, u.username(), u.displayName(), u.role(), u.passwordHash(),
                    u.active(), u.mustChangePassword(), u.failedLoginAttempts(), u.lockedUntil(),
                    u.createdAt(), u.updatedAt(), u.version() + 1);
            byId.put(id, s);
            return s;
        }
        @Override public Optional<UserAccount> findById(long id) { return Optional.ofNullable(byId.get(id)); }
        @Override public Optional<UserAccount> findByUsernameIgnoreCase(String un) {
            return byId.values().stream().filter(u -> u.username().equalsIgnoreCase(un)).findFirst();
        }
        @Override public List<UserAccount> findAll() { return List.copyOf(byId.values()); }
        @Override public boolean existsByRole(UserRole role) {
            return byId.values().stream().anyMatch(u -> u.role() == role);
        }
    }

    public static class InMemoryUserSessionRepo implements UserSessionRepository {
        private final Map<String, UserSessionToken> map = new ConcurrentHashMap<>();
        @Override public void save(UserSessionToken s) { map.put(s.tokenHash(), s); }
        @Override public Optional<UserSessionToken> findByTokenHash(String h) { return Optional.ofNullable(map.get(h)); }
        @Override public void deleteByTokenHash(String h) { map.remove(h); }
        @Override public void deleteByUserId(long uid) { map.values().removeIf(s -> Objects.equals(s.userId(), uid)); }
        @Override public void deleteExpired(Instant now) { map.values().removeIf(s -> !s.expiresAt().isAfter(now)); }
    }

    public static class InMemoryAuditPort implements AuditPort {
        private final CryptoPort crypto;
        private final ClockPort clock;
        public final List<AuditLogEntry> entries = new ArrayList<>();

        public InMemoryAuditPort(CryptoPort crypto, ClockPort clock) {
            this.crypto = crypto;
            this.clock = clock;
        }

        @Override
        public synchronized AuditLogEntry append(String eventType, String actor, String role, String entityType, String entityId, String detailsJson) {
            long seqNo = entries.size() + 1L;
            String prevHash = entries.isEmpty() ? AuditChainCalculator.GENESIS_PREV_HASH : entries.get(entries.size() - 1).entryHash();
            Instant now = clock.now();
            String entryHash = AuditChainCalculator.computeEntryHash(
                    crypto, seqNo, now, eventType, actor, role, entityType, entityId, detailsJson, prevHash);
            AuditLogEntry e = new AuditLogEntry(seqNo, seqNo, eventType, actor, role, entityType, entityId, detailsJson, now, prevHash, entryHash);
            entries.add(e);
            return e;
        }

        @Override
        public List<AuditLogEntry> findRecent(int limit, String eventType, String actor) {
            return entries.stream()
                    .filter(e -> eventType == null || eventType.equals(e.eventType()))
                    .filter(e -> actor == null || actor.equals(e.actorUsername()))
                    .sorted(Comparator.comparingLong(AuditLogEntry::seqNo).reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public AuditVerificationResult verifyChain() {
            return AuditChainCalculator.verify(crypto, entries);
        }
    }
}
