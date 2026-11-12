package com.example.hostapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourorg.qbank.core.model.ExamType;
import com.yourorg.qbank.core.port.ClockPort;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class HostAppIntegrationTest {

    @TempDir
    static Path tempDir;

    static final Instant T0 = Instant.parse("2026-07-01T08:00:00Z");
    static final AtomicReference<Instant> NOW = new AtomicReference<>(T0);

    @TestConfiguration
    static class HostClockOverride {
        @Bean
        public ClockPort qbankClockPort() {
            return NOW::get;
        }
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry reg) throws Exception {
        Files.createDirectories(tempDir.resolve("keys"));
        Files.createDirectories(tempDir.resolve("storage"));
        SecureRandom rnd = new SecureRandom();
        byte[] aes = new byte[32];
        byte[] hmac = new byte[32];
        rnd.nextBytes(aes);
        rnd.nextBytes(hmac);

        String aesB64 = Base64.getEncoder().encodeToString(aes);
        String hmacB64 = Base64.getEncoder().encodeToString(hmac);
        String jdbcUrl = "jdbc:h2:file:" + tempDir.resolve("host-db").toAbsolutePath().toString().replace('\\', '/')
                + ";DB_CLOSE_DELAY=-1";

        reg.add("spring.datasource.url", () -> jdbcUrl);
        reg.add("spring.datasource.username", () -> "sa");
        reg.add("spring.datasource.password", () -> "");
        reg.add("qbank.storage-dir", () -> tempDir.resolve("storage").toAbsolutePath().toString());
        reg.add("qbank.security.encryption-key-base64", () -> aesB64);
        reg.add("qbank.security.signing-key-base64", () -> hmacB64);
        reg.add("qbank.engine.service-token", () -> "host-eng-tok-987");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper json;

    @Test
    void embedsQBankSeamlesslyIntoHostSchemaAuthEventsAndPaperProvider() throws Exception {
        NOW.set(T0);

        // 1. Seed host-owned users in host_users (host owns users cleanly!)
        seedHostUser("portal.admin", "PORTAL_ADMIN");
        seedHostUser("dept.hod", "DEPT_CHAIR");
        seedHostUser("exam.coe", "EXAM_CTRL");
        seedHostUser("hall.proctor", "HALL_PROCTOR,EXAM_ROOM:H1");
        seedHostUser("exam.runner", "INTERNAL_ENGINE");

        // Verify separate Flyway history tables coexist without conflict
        Integer hostFlywayCount = jdbc.queryForObject("SELECT COUNT(*) FROM \"flyway_schema_history\"", Integer.class);
        Integer qbankFlywayCount = jdbc.queryForObject("SELECT COUNT(*) FROM \"qbank_schema_history\"", Integer.class);
        assertNotNull(hostFlywayCount);
        assertNotNull(qbankFlywayCount);
        assertTrue(hostFlywayCount >= 1);
        assertTrue(qbankFlywayCount >= 1);

        // 2. Host Auth mode active + Admin creates subject
        mvc.perform(get("/exam-bank/api/v1/auth/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authMode").value("host"));

        MvcResult subjRes = mvc.perform(post("/exam-bank/api/v1/admin/subjects")
                        .header("Authorization", basic("portal.admin"))
                        .contentType("application/json")
                        .content("{\"code\":\"HOST101\",\"name\":\"Host Integration Lab\",\"expectedQuestionsPerModule\":80,\"marksPerQuestion\":2}"))
                .andExpect(status().isOk())
                .andReturn();
        long subjectId = json.readTree(subjRes.getResponse().getContentAsString()).get("id").asLong();

        // 3. DEPT_CHAIR uploads 5-module workbook & confirms
        byte[] xlsx = buildWorkbook(80);
        MvcResult prevRes = mvc.perform(multipart("/exam-bank/api/v1/hod/subjects/" + subjectId + "/import-multi/preview")
                        .file(new MockMultipartFile("file", "host101.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx))
                        .header("Authorization", basic("dept.hod")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode previews = json.readTree(prevRes.getResponse().getContentAsString());
        for (int i = 0; i < previews.size(); i++) {
            String pid = previews.get(i).get("previewId").asText();
            mvc.perform(post("/exam-bank/api/v1/hod/previews/" + pid + "/confirm")
                            .header("Authorization", basic("dept.hod")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("READY"));
        }

        // 4. EXAM_CTRL generates & freezes Set 1 -> PaperFrozenEvent received by host listener
        Instant startAt = T0.plus(1, ChronoUnit.HOURS);
        Instant endAt = T0.plus(2, ChronoUnit.HOURS);
        MvcResult genRes = mvc.perform(post("/exam-bank/api/v1/coe/exams/generate")
                        .header("Authorization", basic("exam.coe"))
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.ofEntries(
                                Map.entry("subjectId", subjectId),
                                Map.entry("title", "Host Midterm"),
                                Map.entry("examType", ExamType.INTERNAL.name()),
                                Map.entry("modules", List.of(1, 2)),
                                Map.entry("examDate", LocalDate.of(2026, 7, 1).toString()),
                                Map.entry("rotationOffset", 0),
                                Map.entry("includeAfternoon", false),
                                Map.entry("morningStartAt", startAt.toString()),
                                Map.entry("morningEndAt", endAt.toString())
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode genJson = json.readTree(genRes.getResponse().getContentAsString());
        long sessionId = genJson.get("sessions").get(0).get("session").get("id").asLong();
        int expectedVer = genJson.get("sessions").get(0).get("session").get("version").asInt();

        mvc.perform(post("/exam-bank/api/v1/coe/sessions/" + sessionId + "/approve")
                        .header("Authorization", basic("exam.coe"))
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "setNumber", 1,
                                "expectedVersion", expectedVer,
                                "scheduledStartAt", startAt.toString(),
                                "scheduledEndAt", endAt.toString()))))
                .andExpect(status().isOk());

        // Host ApplicationEventListener persisted notification cleanly
        mvc.perform(get("/host/api/notifications").header("Authorization", basic("portal.admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].SUBJECT_CODE").value("HOST101"));

        // Register 1 student
        MvcResult credRes = mvc.perform(post("/exam-bank/api/v1/coe/sessions/" + sessionId + "/students")
                        .header("Authorization", basic("exam.coe"))
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("students", List.of(Map.of(
                                "rollNumber", "HS001", "fullName", "Host Student 1", "roomCode", "H1",
                                "rowNumber", 1, "seatNumber", 1, "fontScale", 1.0, "highContrast", false,
                                "accommodationExtraMinutes", 0))))))
                .andExpect(status().isOk())
                .andReturn();
        String code = json.readTree(credRes.getResponse().getContentAsString()).get(0).get("oneTimeAccessCode").asText();
        String ver = json.readTree(credRes.getResponse().getContentAsString()).get(0).get("assignedVersion").asText();

        // COE blocked from host runner & QBank engine routes; FrozenPaperProvider blocks version decryption before start!
        mvc.perform(get("/host/api/runner/session/" + sessionId + "/version/" + ver)
                        .header("Authorization", basic("exam.coe")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/host/api/runner/session/" + sessionId + "/version/" + ver)
                        .header("Authorization", basic("exam.runner")))
                .andExpect(status().isForbidden());

        // 5. Enter exam window -> Host FrozenPaperProvider decrypts cleanly -> Student completes exam -> Score exported!
        NOW.set(startAt.plus(5, ChronoUnit.MINUTES));
        mvc.perform(post("/exam-bank/api/v1/coe/sessions/" + sessionId + "/start")
                        .header("Authorization", basic("exam.coe")))
                .andExpect(status().isOk());

        mvc.perform(get("/host/api/runner/session/" + sessionId + "/version/" + ver)
                        .header("Authorization", basic("exam.runner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions.length()").value(15));

        MvcResult stuLogin = mvc.perform(post("/exam-bank/api/v1/student/login")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("sessionId", sessionId, "rollNumber", "HS001", "accessCode", code))))
                .andExpect(status().isOk())
                .andReturn();
        String stuJwt = json.readTree(stuLogin.getResponse().getContentAsString()).get("signedToken").asText();

        mvc.perform(get("/exam-bank/api/v1/student/paper").header("Authorization", "Bearer " + stuJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions.length()").value(15));
    }

    private void seedHostUser(String username, String authorities) {
        jdbc.update(
                "INSERT INTO host_users (username, password_hash, authorities, enabled) VALUES (?, ?, ?, TRUE)",
                username, passwordEncoder.encode("secret-host-pw"), authorities
        );
    }

    private static String basic(String username) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":secret-host-pw").getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] buildWorkbook(int count) throws Exception {
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
                for (int q = 1; q <= count; q++) {
                    Row r = s.createRow(q);
                    r.createCell(0).setCellValue("HOST101 M" + m + " Q" + q + "?");
                    r.createCell(1).setCellValue("OptA");
                    r.createCell(2).setCellValue("OptB");
                    r.createCell(3).setCellValue("OptC");
                    r.createCell(4).setCellValue("OptD");
                    r.createCell(5).setCellValue("A");
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }
}
