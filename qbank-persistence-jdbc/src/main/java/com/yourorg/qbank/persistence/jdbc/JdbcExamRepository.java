package com.yourorg.qbank.persistence.jdbc;

import com.yourorg.qbank.core.model.*;
import com.yourorg.qbank.core.port.ExamRepository;
import com.yourorg.qbank.core.port.JsonCodecPort;
import com.yourorg.qbank.persistence.JdbcTransactionManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

public class JdbcExamRepository implements ExamRepository {

    private final JdbcTransactionManager tx;
    private final JsonCodecPort jsonCodec;

    public JdbcExamRepository(JdbcTransactionManager tx, JsonCodecPort jsonCodec) {
        this.tx = tx;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public Exam saveExam(Exam e) {
        return tx.withConnection(conn -> {
            String sql = """
                    INSERT INTO qbank_exams(subject_id, subject_code, subject_name, title, exam_type, exam_date,
                    marks_per_question, total_marks, total_questions, selected_modules_json, blueprint_json,
                    rotation_used, rng_seed, created_by, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, e.subjectId());
                ps.setString(2, e.subjectCode());
                ps.setString(3, e.subjectName());
                ps.setString(4, e.title());
                ps.setString(5, e.examType().name());
                ps.setString(6, e.examDate().toString());
                ps.setInt(7, e.marksPerQuestion());
                ps.setInt(8, e.totalMarks());
                ps.setInt(9, e.totalQuestions());
                ps.setString(10, jsonCodec.toJson(e.selectedModules()));
                ps.setString(11, jsonCodec.toJson(e.blueprint()));
                ps.setInt(12, e.rotationUsed());
                ps.setLong(13, e.rngSeed());
                ps.setString(14, e.createdBy());
                ps.setLong(15, e.createdAt().toEpochMilli());
                long id = JdbcHelper.executeInsertAndReturnId(ps);
                return new Exam(id, e.subjectId(), e.subjectCode(), e.subjectName(), e.title(), e.examType(),
                        e.examDate(), e.marksPerQuestion(), e.totalMarks(), e.totalQuestions(),
                        e.selectedModules(), e.blueprint(), e.rotationUsed(), e.rngSeed(), e.createdBy(), e.createdAt());
            }
        });
    }

    @Override
    public Optional<Exam> findExamById(long examId) {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM qbank_exams WHERE id = ?")) {
                ps.setLong(1, examId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapExam(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<Exam> findAllExams() {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM qbank_exams ORDER BY id DESC");
                 ResultSet rs = ps.executeQuery()) {
                List<Exam> out = new ArrayList<>();
                while (rs.next()) out.add(mapExam(rs));
                return out;
            }
        });
    }

    @Override
    public List<Exam> findExamsBySubjectId(long subjectId) {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM qbank_exams WHERE subject_id = ? ORDER BY id DESC")) {
                ps.setLong(1, subjectId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Exam> out = new ArrayList<>();
                    while (rs.next()) out.add(mapExam(rs));
                    return out;
                }
            }
        });
    }

    @Override
    public long countExams() {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM qbank_exams");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    @Override
    public ExamSession saveSession(ExamSession s) {
        return tx.withConnection(conn -> {
            if (s.id() == null) {
                String sql = """
                        INSERT INTO qbank_exam_sessions(exam_id, session_slot, status, chosen_set_number,
                        scheduled_start_at, scheduled_end_at, actual_started_at, actual_ended_at,
                        started_by, ended_by, frozen_by, frozen_at, content_sha256, version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
                try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, s.examId());
                    ps.setString(2, s.sessionSlot().name());
                    ps.setString(3, s.status().name());
                    JdbcHelper.setNullableInt(ps, 4, s.chosenSetNumber());
                    ps.setLong(5, s.scheduledStartAt().toEpochMilli());
                    ps.setLong(6, s.scheduledEndAt().toEpochMilli());
                    JdbcHelper.setNullableInstant(ps, 7, s.actualStartedAt());
                    JdbcHelper.setNullableInstant(ps, 8, s.actualEndedAt());
                    ps.setString(9, s.startedBy());
                    ps.setString(10, s.endedBy());
                    ps.setString(11, s.frozenBy());
                    JdbcHelper.setNullableInstant(ps, 12, s.frozenAt());
                    ps.setString(13, s.contentSha256());
                    ps.setInt(14, s.version());
                    long id = JdbcHelper.executeInsertAndReturnId(ps);
                    return new ExamSession(id, s.examId(), s.sessionSlot(), s.status(), s.chosenSetNumber(),
                            s.scheduledStartAt(), s.scheduledEndAt(), s.actualStartedAt(), s.actualEndedAt(),
                            s.startedBy(), s.endedBy(), s.frozenBy(), s.frozenAt(), s.contentSha256(), s.version());
                }
            }
            String sql = "UPDATE qbank_exam_sessions SET scheduled_start_at = ?, scheduled_end_at = ? WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, s.scheduledStartAt().toEpochMilli());
                ps.setLong(2, s.scheduledEndAt().toEpochMilli());
                ps.setLong(3, s.id());
                ps.executeUpdate();
                return s;
            }
        });
    }

    @Override
    public Optional<ExamSession> findSessionById(long sessionId) {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM qbank_exam_sessions WHERE id = ?")) {
                ps.setLong(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapSession(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<ExamSession> findSessionsByExamId(long examId) {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM qbank_exam_sessions WHERE exam_id = ? ORDER BY id ASC")) {
                ps.setLong(1, examId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<ExamSession> out = new ArrayList<>();
                    while (rs.next()) out.add(mapSession(rs));
                    return out;
                }
            }
        });
    }

    @Override
    public List<ExamSession> findAllSessions() {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM qbank_exam_sessions ORDER BY id DESC");
                 ResultSet rs = ps.executeQuery()) {
                List<ExamSession> out = new ArrayList<>();
                while (rs.next()) out.add(mapSession(rs));
                return out;
            }
        });
    }

    @Override
    public List<ExamSession> findFrozenOrLiveSessionsUsingModule(long subjectId, int moduleNumber) {
        List<Exam> exams = findExamsBySubjectId(subjectId);
        List<ExamSession> matching = new ArrayList<>();
        for (Exam exam : exams) {
            if (exam.selectedModules().stream().anyMatch(m -> ModuleUnit.overlaps(m, moduleNumber))) {
                for (ExamSession s : findSessionsByExamId(exam.id())) {
                    if (s.status() == SessionStatus.FROZEN || s.status() == SessionStatus.LIVE) {
                        matching.add(s);
                    }
                }
            }
        }
        return matching;
    }

    @Override
    public PaperSetStored savePaperSet(PaperSetStored psObj) {
        return tx.withConnection(conn -> {
            String sql = "INSERT INTO qbank_paper_sets(session_id, set_number, status, question_count, question_ids_json) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, psObj.sessionId());
                ps.setInt(2, psObj.setNumber());
                ps.setString(3, psObj.status().name());
                ps.setInt(4, psObj.questionCount());
                ps.setString(5, jsonCodec.toJson(psObj.questionIds()));
                long id = JdbcHelper.executeInsertAndReturnId(ps);
                return new PaperSetStored(id, psObj.sessionId(), psObj.setNumber(), psObj.status(), psObj.questionCount(), psObj.questionIds());
            }
        });
    }

    @Override
    public List<PaperSetStored> findSetsBySessionId(long sessionId) {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM qbank_paper_sets WHERE session_id = ? ORDER BY set_number ASC")) {
                ps.setLong(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<PaperSetStored> out = new ArrayList<>();
                    while (rs.next()) out.add(mapPaperSet(rs));
                    return out;
                }
            }
        });
    }

    @Override
    public void updatePaperSetStatus(long setId, PaperSetStatus status) {
        tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE qbank_paper_sets SET status = ? WHERE id = ?")) {
                ps.setString(1, status.name());
                ps.setLong(2, setId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Set<String> findQuestionIdsUsedInSession(long sessionId) {
        Set<String> all = new LinkedHashSet<>();
        for (PaperSetStored s : findSetsBySessionId(sessionId)) {
            all.addAll(s.questionIds());
        }
        return all;
    }

    @Override
    public void savePaperVersion(PaperVersionStored v) {
        tx.withConnection(conn -> {
            String sql = """
                    INSERT INTO qbank_paper_versions(paper_set_id, version_label, questions_iv, questions_cipher,
                    answer_key_iv, answer_key_cipher, content_sha256) VALUES (?, ?, ?, ?, ?, ?, ?)""";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, v.paperSetId());
                ps.setString(2, v.versionLabel());
                ps.setString(3, v.questionsEnc().ivBase64());
                ps.setString(4, v.questionsEnc().cipherBase64());
                ps.setString(5, v.answerKeyEnc().ivBase64());
                ps.setString(6, v.answerKeyEnc().cipherBase64());
                ps.setString(7, v.contentSha256());
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<PaperVersionStored> findSelectedVersion(long sessionId, String versionLabel) {
        return tx.withConnection(conn -> {
            String sql = """
                    SELECT pv.* FROM qbank_paper_versions pv
                    JOIN qbank_paper_sets ps ON ps.id = pv.paper_set_id
                    JOIN qbank_exam_sessions es ON es.id = ps.session_id
                    WHERE es.id = ? AND ps.set_number = es.chosen_set_number AND pv.version_label = ?""";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, sessionId);
                ps.setString(2, versionLabel);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapPaperVersion(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<PaperVersionStored> findVersionsBySetId(long paperSetId) {
        return tx.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM qbank_paper_versions WHERE paper_set_id = ? ORDER BY version_label ASC")) {
                ps.setLong(1, paperSetId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<PaperVersionStored> out = new ArrayList<>();
                    while (rs.next()) out.add(mapPaperVersion(rs));
                    return out;
                }
            }
        });
    }

    @Override
    public boolean updateSessionStatusWithVersion(
            long sessionId, int expectedVersion, SessionStatus newStatus, Integer chosenSetNumber,
            Instant actualStartedAt, Instant actualEndedAt, String startedBy, String endedBy,
            String frozenBy, Instant frozenAt, String contentSha256) {
        return tx.withConnection(conn -> {
            String sql = """
                    UPDATE qbank_exam_sessions SET
                    status = ?, chosen_set_number = ?, actual_started_at = ?, actual_ended_at = ?,
                    started_by = ?, ended_by = ?, frozen_by = ?, frozen_at = ?, content_sha256 = ?,
                    version = version + 1
                    WHERE id = ? AND version = ?""";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, newStatus.name());
                JdbcHelper.setNullableInt(ps, 2, chosenSetNumber);
                JdbcHelper.setNullableInstant(ps, 3, actualStartedAt);
                JdbcHelper.setNullableInstant(ps, 4, actualEndedAt);
                ps.setString(5, startedBy);
                ps.setString(6, endedBy);
                ps.setString(7, frozenBy);
                JdbcHelper.setNullableInstant(ps, 8, frozenAt);
                ps.setString(9, contentSha256);
                ps.setLong(10, sessionId);
                ps.setInt(11, expectedVersion);
                return ps.executeUpdate() == 1;
            }
        });
    }

    private Exam mapExam(ResultSet rs) throws SQLException {
        return new Exam(
                rs.getLong("id"),
                rs.getLong("subject_id"),
                rs.getString("subject_code"),
                rs.getString("subject_name"),
                rs.getString("title"),
                ExamType.valueOf(rs.getString("exam_type")),
                LocalDate.parse(rs.getString("exam_date")),
                rs.getInt("marks_per_question"),
                rs.getInt("total_marks"),
                rs.getInt("total_questions"),
                jsonCodec.fromJsonIntList(rs.getString("selected_modules_json")),
                jsonCodec.fromJsonIntMap(rs.getString("blueprint_json")),
                rs.getInt("rotation_used"),
                rs.getLong("rng_seed"),
                rs.getString("created_by"),
                Instant.ofEpochMilli(rs.getLong("created_at"))
        );
    }

    private static ExamSession mapSession(ResultSet rs) throws SQLException {
        return new ExamSession(
                rs.getLong("id"),
                rs.getLong("exam_id"),
                ExamSessionSlot.valueOf(rs.getString("session_slot")),
                SessionStatus.valueOf(rs.getString("status")),
                JdbcHelper.getNullableInt(rs, "chosen_set_number"),
                Instant.ofEpochMilli(rs.getLong("scheduled_start_at")),
                Instant.ofEpochMilli(rs.getLong("scheduled_end_at")),
                JdbcHelper.getInstant(rs, "actual_started_at"),
                JdbcHelper.getInstant(rs, "actual_ended_at"),
                rs.getString("started_by"),
                rs.getString("ended_by"),
                rs.getString("frozen_by"),
                JdbcHelper.getInstant(rs, "frozen_at"),
                rs.getString("content_sha256"),
                rs.getInt("version")
        );
    }

    private PaperSetStored mapPaperSet(ResultSet rs) throws SQLException {
        return new PaperSetStored(
                rs.getLong("id"),
                rs.getLong("session_id"),
                rs.getInt("set_number"),
                PaperSetStatus.valueOf(rs.getString("status")),
                rs.getInt("question_count"),
                jsonCodec.fromJsonStringList(rs.getString("question_ids_json"))
        );
    }

    private static PaperVersionStored mapPaperVersion(ResultSet rs) throws SQLException {
        return new PaperVersionStored(
                rs.getLong("id"),
                rs.getLong("paper_set_id"),
                rs.getString("version_label"),
                new EncryptedPayload(rs.getString("questions_iv"), rs.getString("questions_cipher")),
                new EncryptedPayload(rs.getString("answer_key_iv"), rs.getString("answer_key_cipher")),
                rs.getString("content_sha256")
        );
    }
}
