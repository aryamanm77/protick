package com.yourorg.qbank.starter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourorg.qbank.core.model.ExamType;
import com.yourorg.qbank.core.model.UserRole;
import com.yourorg.qbank.core.port.ClockPort;
import com.yourorg.qbank.starter.teststandalone.TestHostlessApp;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {TestHostlessApp.class, QBankStandaloneHttpIntegrationTest.ClockOverrideConfig.class}
)
@AutoConfigureMockMvc
class QBankStandaloneHttpIntegrationTest {

    @TempDir
    static Path tempDir;

    static final Instant T0 = Instant.parse("2026-06-01T08:00:00Z");
    static final AtomicReference<Instant> NOW = new AtomicReference<>(T0);
    static final String ENGINE_TOKEN = "eng-test-token-123456";

    @TestConfiguration
    static class ClockOverrideConfig {
        @Bean
        public ClockPort qbankClockPort() {
            return NOW::get;
        }
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry reg) throws Exception {
        Files.createDirectories(tempDir.resolve("keys"));
        Files.createDirectories(tempDir.resolve("db"));
        Files.createDirectories(tempDir.resolve("storage"));

        SecureRandom rnd = new SecureRandom();
        byte[] aes = new byte[32];
        byte[] hmac = new byte[32];
        rnd.nextBytes(aes);
        rnd.nextBytes(hmac);
        Path aesFile = tempDir.resolve("keys/aes.key");
        Path hmacFile = tempDir.resolve("keys/hmac.key");
        Files.writeString(aesFile, Base64.getEncoder().encodeToString(aes), StandardCharsets.UTF_8);
        Files.writeString(hmacFile, Base64.getEncoder().encodeToString(hmac), StandardCharsets.UTF_8);

        String jdbcUrl = "jdbc:h2:file:" + tempDir.resolve("db/qbank-test").toAbsolutePath().toString().replace('\\', '/')
                + ";DB_CLOSE_DELAY=-1";
        reg.add("qbank.base-path", () -> "/exam");
        reg.add("qbank.auth.mode", () -> "standalone");
        reg.add("qbank.datasource.url", () -> jdbcUrl);
        reg.add("qbank.datasource.username", () -> "sa");
        reg.add("qbank.datasource.password", () -> "");
        reg.add("qbank.storage-dir", () -> tempDir.resolve("storage").toAbsolutePath().toString());
        reg.add("qbank.security.encryption-key-file", () -> aesFile.toAbsolutePath().toString());
        reg.add("qbank.security.signing-key-file", () -> hmacFile.toAbsolutePath().toString());
        reg.add("qbank.security.login-rate-limit-per-minute", () -> "100");
        reg.add("qbank.engine.service-token", () -> ENGINE_TOKEN);
        reg.add("qbank.backup.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void fullOfflineLanExamLifecycleThroughRestApi() throws Exception {
        NOW.set(T0);

        // 1. Offline UI static assets mounted under custom basePath (/exam) + strict security headers
        MvcResult indexRes = mvc.perform(get("/exam/index.html"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andReturn();
        String indexHtml = indexRes.getResponse().getContentAsString();
        assertFalse(indexHtml.contains("https://"), "Static UI must never use external CDN URLs");
        assertFalse(indexHtml.contains("http://"), "Static UI must never use external HTTP URLs");

        mvc.perform(get("/exam/student.html")).andExpect(status().isOk());
        mvc.perform(get("/exam/qbank-student-view.js")).andExpect(status().isOk());

        // 2. First-run setup check + Admin bootstrap
        mvc.perform(get("/exam/api/v1/auth/info"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.authMode").value("standalone"))
                .andExpect(jsonPath("$.setupRequired").value(true))
                .andExpect(jsonPath("$.user").value(nullValue()));

        mvc.perform(post("/exam/api/v1/auth/setup-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin1\",\"displayName\":\"Main Admin\",\"password\":\"StrongAdmin#2026\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        // Second setup attempt must fail
        mvc.perform(post("/exam/api/v1/auth/setup-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin2\",\"displayName\":\"Rogue\",\"password\":\"StrongAdmin#2026\"}"))
                .andExpect(status().isConflict());

        String adminToken = loginStaff("admin1", "StrongAdmin#2026");

        // 3. Create HOD, COE, INVIGILATOR with temporary passwords
        long hodId = createUser(adminToken, "hod1", "HOD CS", UserRole.HOD, "HodTemp#1234");
        createUser(adminToken, "coe1", "COE Main", UserRole.COE, "CoeTemp#1234");
        createUser(adminToken, "inv1", "Invigilator A", UserRole.INVIGILATOR, "InvTemp#1234");

        // Forced password change flag + brute-force lockout + Admin unlock
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/exam/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"hod1\",\"password\":\"WrongPass123\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/exam/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"hod1\",\"password\":\"WrongPass123\"}"))
                .andExpect(status().isTooManyRequests());

        // Admin clears lockout
        mvc.perform(put("/exam/api/v1/admin/users/" + hodId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":true,\"clearLockout\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedLoginAttempts").value(0));

        String hodToken = loginStaff("hod1", "HodTemp#1234");
        mvc.perform(get("/exam/api/v1/auth/info").header("Authorization", "Bearer " + hodToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.mustChangePassword").value(true));

        mvc.perform(post("/exam/api/v1/auth/change-password")
                        .header("Authorization", "Bearer " + hodToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"HodTemp#1234\",\"newPassword\":\"HodFinal#2026\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/exam/api/v1/auth/info").header("Authorization", "Bearer " + hodToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.mustChangePassword").value(false));

        String coeToken = loginStaff("coe1", "CoeTemp#1234");
        String invToken = loginStaff("inv1", "InvTemp#1234");

        // 4. Admin creates subject CS101 (expected 120/module, 2 marks/question)
        MvcResult subjRes = mvc.perform(post("/exam/api/v1/admin/subjects")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"CS101\",\"name\":\"Algorithms & Data Structures\",\"expectedQuestionsPerModule\":120,\"marksPerQuestion\":2}"))
                .andExpect(status().isOk())
                .andReturn();
        long subjectId = json.readTree(subjRes.getResponse().getContentAsString()).get("id").asLong();

        // Cross-role denials enforced server-side
        mvc.perform(get("/exam/api/v1/hod/template").header("Authorization", "Bearer " + coeToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/exam/api/v1/hod/template?multi=true").header("Authorization", "Bearer " + hodToken))
                .andExpect(status().isOk());
        mvc.perform(get("/exam/api/v1/coe/sessions").header("Authorization", "Bearer " + hodToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/exam/api/v1/subjects")
                        .header("Authorization", "Bearer " + hodToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"CS102\",\"name\":\"Discrete Math\",\"expectedQuestionsPerModule\":120,\"marksPerQuestion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("CS102"));
        mvc.perform(post("/exam/api/v1/subjects")
                        .header("Authorization", "Bearer " + invToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"CS103\",\"name\":\"Blocked\",\"expectedQuestionsPerModule\":120,\"marksPerQuestion\":1}"))
                .andExpect(status().isForbidden());

        // 5. HOD previews 5-module workbook (40 valid Qs/module = 200 total; row 2 on M1 has empty stem to test inline fix)
        byte[] workbookBytes = buildFiveModuleWorkbookWithOneBadRow();
        MvcResult previewRes = mvc.perform(multipart("/exam/api/v1/hod/subjects/" + subjectId + "/import-multi/preview")
                        .file(new MockMultipartFile("file", "cs101-bank.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbookBytes))
                        .header("Authorization", "Bearer " + hodToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode previews = json.readTree(previewRes.getResponse().getContentAsString());
        assertEquals(5, previews.size());

        // Fix bad row on Module 1 preview before confirming all 5 modules
        String m1PreviewId = previews.get(0).get("previewId").asText();
        assertTrue(previews.get(0).get("errorCount").asInt() >= 1);
        mvc.perform(put("/exam/api/v1/hod/previews/" + m1PreviewId + "/rows/3")
                        .header("Authorization", "Bearer " + hodToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questionText\":\"Fixed M1 Question 2?\",\"options\":[\"A1\",\"B1\",\"C1\",\"D1\"],\"correctIndex\":1,\"excluded\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCount").value(0));

            for (int i = 0; i < 5; i++) {
            String pid = previews.get(i).get("previewId").asText();
            MvcResult confirmRes = mvc.perform(post("/exam/api/v1/hod/previews/" + pid + "/confirm")
                            .header("Authorization", "Bearer " + hodToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("READY"))
                    .andExpect(jsonPath("$.questionCount").value(80))
                    .andReturn();
            String confirmBody = confirmRes.getResponse().getContentAsString();
            assertFalse(confirmBody.contains("Fixed M1 Question 2?"), "HOD confirm must not leak shuffled question stems");
            assertFalse(confirmBody.contains("CS101-M"), "HOD confirm must not leak question IDs");

            // Draft preview deleted on confirm
            mvc.perform(get("/exam/api/v1/hod/previews/" + pid).header("Authorization", "Bearer " + hodToken))
                    .andExpect(status().isNotFound());
        }

        // 6. COE generates Dual Session INTERNAL on modules [1, 2]
        Instant mStart = T0.plus(1, ChronoUnit.HOURS);
        Instant mEnd = T0.plus(2, ChronoUnit.HOURS);
        Instant aStart = T0.plus(4, ChronoUnit.HOURS);
        Instant aEnd = T0.plus(5, ChronoUnit.HOURS);

        String genPayload = json.writeValueAsString(Map.ofEntries(
                Map.entry("subjectId", subjectId),
                Map.entry("title", "Internal Assessment 1"),
                Map.entry("examType", ExamType.INTERNAL),
                Map.entry("modules", List.of(1, 2)),
                Map.entry("examDate", LocalDate.of(2026, 6, 1).toString()),
                Map.entry("rotationOffset", 0),
                Map.entry("includeAfternoon", true),
                Map.entry("morningStartAt", mStart.toString()),
                Map.entry("morningEndAt", mEnd.toString()),
                Map.entry("afternoonStartAt", aStart.toString()),
                Map.entry("afternoonEndAt", aEnd.toString())
        ));

        MvcResult genRes = mvc.perform(post("/exam/api/v1/coe/exams/generate")
                        .header("Authorization", "Bearer " + coeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(genPayload))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode genJson = json.readTree(genRes.getResponse().getContentAsString());
        long morningSessionId = genJson.get("sessions").get(0).get("session").get("id").asLong();
        long afternoonSessionId = genJson.get("sessions").get(1).get("session").get("id").asLong();
        int morningExpectedVer = genJson.get("sessions").get(0).get("session").get("version").asInt();
        int afternoonExpectedVer = genJson.get("sessions").get(1).get("session").get("version").asInt();

        // COE sees ONLY SetSummaryCards (no questions/options/IDs/keys)
        MvcResult setsRes = mvc.perform(get("/exam/api/v1/coe/sessions/" + morningSessionId + "/sets")
                        .header("Authorization", "Bearer " + coeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andReturn();
        String setsBody = setsRes.getResponse().getContentAsString();
        assertFalse(setsBody.contains("CS101-M"));
        assertFalse(setsBody.contains("Fixed M1 Question"));
        assertFalse(setsBody.contains("correctIndex"));

        // COE strictly forbidden from Engine API
        mvc.perform(get("/exam/api/v1/engine/sessions/" + morningSessionId)
                        .header("Authorization", "Bearer " + coeToken))
                .andExpect(status().isForbidden());

        // Confirmation preview + Approve/Freeze both sessions
        mvc.perform(get("/exam/api/v1/coe/sessions/" + morningSessionId + "/sets/1/approval-preview")
                        .header("Authorization", "Bearer " + coeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setNumber").value(1));

        mvc.perform(post("/exam/api/v1/coe/sessions/" + morningSessionId + "/approve")
                        .header("Authorization", "Bearer " + coeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "setNumber", 1,
                                "expectedVersion", morningExpectedVer,
                                "scheduledStartAt", mStart.toString(),
                                "scheduledEndAt", mEnd.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));

        mvc.perform(post("/exam/api/v1/coe/sessions/" + afternoonSessionId + "/approve")
                        .header("Authorization", "Bearer " + coeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "setNumber", 2,
                                "expectedVersion", afternoonExpectedVer,
                                "scheduledStartAt", aStart.toString(),
                                "scheduledEndAt", aEnd.toString()))))
                .andExpect(status().isOk());

        // Register students STU001 (=10+5 prefix tests formula sanitization later!) & STU002 in ROOM-A
        MvcResult rosterRes = mvc.perform(post("/exam/api/v1/coe/sessions/" + morningSessionId + "/students")
                        .header("Authorization", "Bearer " + coeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("students", List.of(
                                Map.of("rollNumber", "STU001", "fullName", "=SUM(1+1) Alice", "roomCode", "ROOM-A",
                                        "rowNumber", 1, "seatNumber", 1, "fontScale", 1.25, "highContrast", true,
                                        "accommodationExtraMinutes", 10),
                                Map.of("rollNumber", "STU002", "fullName", "Bob", "roomCode", "ROOM-A",
                                        "rowNumber", 1, "seatNumber", 2, "fontScale", 1.0, "highContrast", false,
                                        "accommodationExtraMinutes", 0)
                        )))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode creds = json.readTree(rosterRes.getResponse().getContentAsString());
        String stu1Code = creds.get(0).get("oneTimeAccessCode").asText();
        String stu1Version = creds.get(0).get("assignedVersion").asText();

        // 7. Window enforcement BEFORE start: Student login & Engine decryption both blocked!
        mvc.perform(post("/exam/api/v1/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("sessionId", morningSessionId, "rollNumber", "STU001", "accessCode", stu1Code))))
                .andExpect(status().isForbidden());

        mvc.perform(get("/exam/api/v1/engine/sessions/" + morningSessionId + "/versions/" + stu1Version)
                        .header("X-QBank-Engine-Token", ENGINE_TOKEN))
                .andExpect(status().isForbidden());

        // 8. Advance clock into exam window -> Start session -> Engine + Student active!
        NOW.set(mStart.plus(2, ChronoUnit.MINUTES));
        mvc.perform(post("/exam/api/v1/coe/sessions/" + morningSessionId + "/start")
                        .header("Authorization", "Bearer " + coeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LIVE"));

        // Verify wrong engine token rejected, valid engine token retrieves key during window
        mvc.perform(get("/exam/api/v1/engine/sessions/" + morningSessionId + "/versions/" + stu1Version + "/key")
                        .header("X-QBank-Engine-Token", "wrong-token"))
                .andExpect(status().isForbidden());
        MvcResult engineKeyRes = mvc.perform(get("/exam/api/v1/engine/sessions/" + morningSessionId + "/versions/" + stu1Version + "/key")
                        .header("X-QBank-Engine-Token", ENGINE_TOKEN))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode keyJson = json.readTree(engineKeyRes.getResponse().getContentAsString());
        assertEquals(15, keyJson.size()); // INTERNAL @ 2 marks/Q => 15 questions across modules [1,2]
        int correctOptPos1 = keyJson.get(0).get("correctIndex").asInt();

        // Student logs in, loads paper (zero keys/IDs leaked!)
        MvcResult stuLoginRes = mvc.perform(post("/exam/api/v1/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("sessionId", morningSessionId, "rollNumber", "STU001", "accessCode", stu1Code))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedVersion").value(stu1Version))
                .andExpect(jsonPath("$.highContrast").value(true))
                .andReturn();
        String stuJwt = json.readTree(stuLoginRes.getResponse().getContentAsString()).get("signedToken").asText();

        MvcResult paperRes = mvc.perform(get("/exam/api/v1/student/paper")
                        .header("Authorization", "Bearer " + stuJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions.length()").value(15))
                .andReturn();
        String paperBody = paperRes.getResponse().getContentAsString();
        assertFalse(paperBody.contains("correctIndex"), "Student paper must never include correctIndex");
        assertFalse(paperBody.contains("CS101-M"), "Student paper must never include MCQ IDs");

        // Second concurrent login blocked (409)
        mvc.perform(post("/exam/api/v1/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("sessionId", morningSessionId, "rollNumber", "STU001", "accessCode", stu1Code))))
                .andExpect(status().isConflict());

        // Student autosaves Q1 correctly, then Invigilator monitors + grants +5 extra minutes + resets login for PC swap!
        mvc.perform(post("/exam/api/v1/student/responses")
                        .header("Authorization", "Bearer " + stuJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "answers", List.of(Map.of(
                                        "questionPosition", 1,
                                        "selectedOptionIndex", correctOptPos1,
                                        "clientTimestamp", NOW.get().toString())),
                                "lastQuestionIndex", 3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedCount").value(1));

        mvc.perform(get("/exam/api/v1/invigilator/sessions/" + morningSessionId + "/monitor")
                        .param("roomCode", "ROOM-A")
                        .header("Authorization", "Bearer " + invToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeCount").value(1))
                .andExpect(jsonPath("$.students[0].answeredCount").value(1));

        mvc.perform(post("/exam/api/v1/invigilator/sessions/" + morningSessionId + "/extra-time")
                        .header("Authorization", "Bearer " + invToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomCode\":\"ROOM-A\",\"rollNumber\":null,\"extraMinutes\":5,\"reason\":\"Power flicker\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/exam/api/v1/invigilator/sessions/" + morningSessionId + "/students/STU001/reset-login")
                        .header("Authorization", "Bearer " + invToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Student terminal crashed\"}"))
                .andExpect(status().isOk());

        // Resumed on new PC with preserved position + answers!
        MvcResult resumedLogin = mvc.perform(post("/exam/api/v1/student/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("sessionId", morningSessionId, "rollNumber", "STU001", "accessCode", stu1Code))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastQuestionIndex").value(3))
                .andReturn();
        String resumedJwt = json.readTree(resumedLogin.getResponse().getContentAsString()).get("signedToken").asText();

        mvc.perform(post("/exam/api/v1/student/finish")
                        .header("Authorization", "Bearer " + resumedJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastQuestionIndex\":3}"))
                .andExpect(status().isOk());

        // 9. Advance past end -> Stop -> Formula-safe Results CSV/XLSX -> Audit verify -> Encrypted Backup
        NOW.set(mEnd.plus(20, ChronoUnit.MINUTES));
        mvc.perform(post("/exam/api/v1/coe/sessions/" + morningSessionId + "/stop")
                        .header("Authorization", "Bearer " + coeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"));

        MvcResult csvRes = mvc.perform(get("/exam/api/v1/admin/sessions/" + morningSessionId + "/results.csv")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Content-SHA256"))
                .andReturn();
        String csvText = csvRes.getResponse().getContentAsString();
        assertTrue(csvText.contains("'=SUM(1+1) Alice"), "CSV must neutralize leading '=' formula injection");

        mvc.perform(get("/exam/api/v1/admin/sessions/" + morningSessionId + "/results")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].correctCount").value(1))
                .andExpect(jsonPath("$.results[0].unansweredCount").value(14))
                .andExpect(jsonPath("$.results[0].marksAwarded").value(2));

        mvc.perform(post("/exam/api/v1/admin/audit/verify")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intact").value(true));

        MvcResult backupRes = mvc.perform(post("/exam/api/v1/admin/backups")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        String backupFile = json.readTree(backupRes.getResponse().getContentAsString()).get("filename").asText();
        assertTrue(backupFile.endsWith(".qbank.enc"));

        byte[] backupBytes = mvc.perform(get("/exam/api/v1/admin/backups/" + backupFile)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        mvc.perform(multipart("/exam/api/v1/admin/backups/inspect")
                        .file(new MockMultipartFile("file", backupFile, "application/octet-stream", backupBytes))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCounts.qbank_mcqs").value(400));

        // 9. Half-module HTTP workflow: 1.5 modules (M1 Full + M2 1st Half) -> 20 + 10 blueprint, freeze M2A without blocking M2B!
        MvcResult cs105Res = mvc.perform(post("/exam/api/v1/subjects")
                        .header("Authorization", "Bearer " + hodToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"CS105\",\"name\":\"Operating Systems\",\"expectedQuestionsPerModule\":120,\"marksPerQuestion\":1}"))
                .andExpect(status().isOk())
                .andReturn();
        long cs105Id = json.readTree(cs105Res.getResponse().getContentAsString()).get("id").asLong();

        byte[] m1Wb = buildSingleSheetWorkbook("Module 1", 80);
        byte[] m2aWb = buildSingleSheetWorkbook("Module 2 (1st Half)", 50);
        byte[] m2bWb = buildSingleSheetWorkbook("Module 2 (2nd Half)", 50);

        String p1Id = json.readTree(mvc.perform(multipart("/exam/api/v1/hod/subjects/" + cs105Id + "/modules/1/preview")
                        .file(new MockMultipartFile("file", "m1.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", m1Wb))
                        .header("Authorization", "Bearer " + hodToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("previewId").asText();
        mvc.perform(post("/exam/api/v1/hod/previews/" + p1Id + "/confirm").header("Authorization", "Bearer " + hodToken))
                .andExpect(status().isOk());

        String p2aId = json.readTree(mvc.perform(multipart("/exam/api/v1/hod/subjects/" + cs105Id + "/modules/21/preview")
                        .file(new MockMultipartFile("file", "m2a.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", m2aWb))
                        .header("Authorization", "Bearer " + hodToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedCount").value(60))
                .andReturn().getResponse().getContentAsString()).get("previewId").asText();
        mvc.perform(post("/exam/api/v1/hod/previews/" + p2aId + "/confirm").header("Authorization", "Bearer " + hodToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moduleNumber").value(21))
                .andExpect(jsonPath("$.questionCount").value(50));

        // COE previews 1.5-module blueprint -> 30 questions (20 M1 + 10 M2A)
        mvc.perform(post("/exam/api/v1/coe/blueprint")
                        .header("Authorization", "Bearer " + coeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":" + cs105Id + ",\"examType\":\"INTERNAL\",\"modules\":[1,21],\"rotationOffset\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalQuestions").value(30))
                .andExpect(jsonPath("$.moduleCounts['1']").value(20))
                .andExpect(jsonPath("$.moduleCounts['21']").value(10));

        Instant hStart = NOW.get().plus(1, ChronoUnit.DAYS);
        Instant hEnd = hStart.plus(1, ChronoUnit.HOURS);
        String iaPayload = json.writeValueAsString(Map.of(
                "subjectId", cs105Id,
                "title", "OS IA-1 (1.5 Modules)",
                "examType", ExamType.INTERNAL,
                "modules", List.of(1, 21),
                "examDate", LocalDate.of(2026, 6, 15).toString(),
                "rotationOffset", 0,
                "includeAfternoon", false,
                "morningStartAt", hStart.toString(),
                "morningEndAt", hEnd.toString()
        ));
        JsonNode iaGen = json.readTree(mvc.perform(post("/exam/api/v1/coe/exams/generate")
                        .header("Authorization", "Bearer " + coeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(iaPayload))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        long iaSid = iaGen.get("sessions").get(0).get("session").get("id").asLong();
        int iaVer = iaGen.get("sessions").get(0).get("session").get("version").asInt();

        mvc.perform(post("/exam/api/v1/coe/sessions/" + iaSid + "/approve")
                        .header("Authorization", "Bearer " + coeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "setNumber", 1,
                                "expectedVersion", iaVer,
                                "scheduledStartAt", hStart.toString(),
                                "scheduledEndAt", hEnd.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));

        // M2A (21) is locked (409), while M2B (22) succeeds cleanly!
        String p2aLocked = json.readTree(mvc.perform(multipart("/exam/api/v1/hod/subjects/" + cs105Id + "/modules/21/preview")
                        .file(new MockMultipartFile("file", "m2a.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", m2aWb))
                        .header("Authorization", "Bearer " + hodToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("previewId").asText();
        mvc.perform(post("/exam/api/v1/hod/previews/" + p2aLocked + "/confirm").header("Authorization", "Bearer " + hodToken))
                .andExpect(status().isConflict());

        String p2bId = json.readTree(mvc.perform(multipart("/exam/api/v1/hod/subjects/" + cs105Id + "/modules/22/preview")
                        .file(new MockMultipartFile("file", "m2b.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", m2bWb))
                        .header("Authorization", "Bearer " + hodToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("previewId").asText();
        mvc.perform(post("/exam/api/v1/hod/previews/" + p2bId + "/confirm").header("Authorization", "Bearer " + hodToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moduleNumber").value(22))
                .andExpect(jsonPath("$.status").value("READY"));
    }

    private static byte[] buildSingleSheetWorkbook(String sheetName, int count) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet(sheetName);
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("Question");
            h.createCell(1).setCellValue("Option A");
            h.createCell(2).setCellValue("Option B");
            h.createCell(3).setCellValue("Option C");
            h.createCell(4).setCellValue("Option D");
            h.createCell(5).setCellValue("Answer");
            for (int q = 1; q <= count; q++) {
                Row r = s.createRow(q);
                r.createCell(0).setCellValue(sheetName + " Question #" + q + "?");
                r.createCell(1).setCellValue("OptA-" + q);
                r.createCell(2).setCellValue("OptB-" + q);
                r.createCell(3).setCellValue("OptC-" + q);
                r.createCell(4).setCellValue("OptD-" + q);
                r.createCell(5).setCellValue("A");
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private String loginStaff(String username, String password) throws Exception {
        MvcResult res = mvc.perform(post("/exam/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    private long createUser(String adminToken, String username, String displayName, UserRole role, String tempPass) throws Exception {
        MvcResult res = mvc.perform(post("/exam/api/v1/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "username", username,
                                "displayName", displayName,
                                "role", role.name(),
                                "temporaryPassword", tempPass
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private static byte[] buildFiveModuleWorkbookWithOneBadRow() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int m = 1; m <= 5; m++) {
                Sheet s = wb.createSheet("Module " + m);
                Row h = s.createRow(0);
                h.createCell(0).setCellValue("Question");
                h.createCell(1).setCellValue("Option A");
                h.createCell(2).setCellValue("Option B");
                h.createCell(3).setCellValue("Option C");
                h.createCell(4).setCellValue("Option D");
                h.createCell(5).setCellValue("Answer");
                for (int q = 1; q <= 80; q++) {
                    Row r = s.createRow(q);
                    if (m == 1 && q == 2) {
                        r.createCell(0).setCellValue(""); // triggers fixable error on sourceRow 3 (1-indexed Excel row)
                    } else {
                        r.createCell(0).setCellValue("M" + m + " Q" + q + "?");
                    }
                    r.createCell(1).setCellValue("OptA-" + m + "-" + q);
                    r.createCell(2).setCellValue("OptB-" + m + "-" + q);
                    r.createCell(3).setCellValue("OptC-" + m + "-" + q);
                    r.createCell(4).setCellValue("OptD-" + m + "-" + q);
                    r.createCell(5).setCellValue("B");
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }
}
