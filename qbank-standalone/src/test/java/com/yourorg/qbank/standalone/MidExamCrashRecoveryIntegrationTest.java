package com.yourorg.qbank.standalone;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MidExamCrashRecoveryIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void survivesForcibleJvmKillMidExamWithinTwoMinutesWithZeroLostAnswers() throws Exception {
        Path dbFile = tempDir.resolve("data/qbank-crash-db");
        Path storageDir = tempDir.resolve("data/storage");
        StandaloneTestSupport.KeyPairPaths keys = StandaloneTestSupport.writeRandomKeys(tempDir.resolve("secrets"));
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String engineToken = "crash-engine-token-xyz";

        int port1 = StandaloneTestSupport.freePort();
        Path log1 = tempDir.resolve("logs/jvm1.log");
        Process proc1 = StandaloneTestSupport.spawnServer(port1, dbFile, storageDir, keys, engineToken, 100, log1);
        try {
            StandaloneTestSupport.waitForReady(client, port1, proc1, log1, Duration.ofSeconds(60));
            String base1 = "http://127.0.0.1:" + port1 + "/qbank";

            var ctx = StandaloneTestSupport.bootstrapLiveSession(client, base1, 1);
            String roll = "R0001";
            String code = ctx.accessCodes().get(roll);
            String version = ctx.assignedVersions().get(roll);

            // Engine key for deterministic scoring verification later
            JsonNode engineKey = StandaloneTestSupport.getJson(
                    client, base1 + "/api/v1/engine/sessions/" + ctx.sessionId() + "/versions/" + version + "/key",
                    null, "X-QBank-Engine-Token", engineToken);

            // Student logs in and submits Q1..Q5 (first 5 answered correctly)
            JsonNode stuLogin = StandaloneTestSupport.postJson(client, base1 + "/api/v1/student/login", null, Map.of(
                    "sessionId", ctx.sessionId(), "rollNumber", roll, "accessCode", code));
            String stuJwt = stuLogin.get("signedToken").asText();

            List<Map<String, Object>> answers = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                answers.add(Map.of(
                        "questionPosition", i + 1,
                        "selectedOptionIndex", engineKey.get(i).get("correctIndex").asInt(),
                        "clientTimestamp", Instant.now().toString()
                ));
            }
            JsonNode ack = StandaloneTestSupport.postJson(client, base1 + "/api/v1/student/responses", stuJwt, Map.of(
                    "answers", answers,
                    "lastQuestionIndex", 5
            ));
            assertEquals(5, ack.get("savedCount").asInt());

            // Simulate sudden crash (hard kill) while exam is LIVE
            proc1.destroyForcibly();
            proc1.waitFor();

            // Reboot second JVM process against exact same persisted DB + external keys
            long recoveryStartNs = System.nanoTime();
            int port2 = StandaloneTestSupport.freePort();
            Path log2 = tempDir.resolve("logs/jvm2.log");
            Process proc2 = StandaloneTestSupport.spawnServer(port2, dbFile, storageDir, keys, engineToken, 100, log2);
            try {
                StandaloneTestSupport.waitForReady(client, port2, proc2, log2, Duration.ofSeconds(90));
                long recoveryMs = (System.nanoTime() - recoveryStartNs) / 1_000_000L;
                assertTrue(recoveryMs < 120_000L, "Recovery exceeded 2-minute SLO: " + recoveryMs + "ms");

                String base2 = "http://127.0.0.1:" + port2 + "/qbank";
                String admin2 = StandaloneTestSupport.loginStaff(client, base2, "admin1", "AdminStrong#2026");
                String inv2 = StandaloneTestSupport.loginStaff(client, base2, "inv1", "InvStrong#2026");
                String coe2 = StandaloneTestSupport.loginStaff(client, base2, "coe1", "CoeStrong#2026");

                // All 5 acknowledged answers survived crash
                JsonNode monitor = StandaloneTestSupport.getJson(
                        client, base2 + "/api/v1/invigilator/sessions/" + ctx.sessionId() + "/monitor?roomCode=HALL-1",
                        inv2, null, null);
                assertEquals(5, monitor.get("students").get(0).get("answeredCount").asInt());
                assertEquals(5, monitor.get("students").get(0).get("lastQuestionIndex").asInt());

                // Student resets & resumes seamlessly on recovered server, submits Q6, finishes, stops & verifies score=12 (6*2)
                StandaloneTestSupport.postJson(client,
                        base2 + "/api/v1/invigilator/sessions/" + ctx.sessionId() + "/students/" + roll + "/reset-login",
                        inv2, Map.of("reason", "Server restarted"));

                JsonNode resumedLogin = StandaloneTestSupport.postJson(client, base2 + "/api/v1/student/login", null, Map.of(
                        "sessionId", ctx.sessionId(), "rollNumber", roll, "accessCode", code));
                String resumedJwt = resumedLogin.get("signedToken").asText();
                assertEquals(5, resumedLogin.get("lastQuestionIndex").asInt());

                JsonNode paper = StandaloneTestSupport.getJson(client, base2 + "/api/v1/student/paper", resumedJwt, null, null);
                assertEquals(5, paper.get("savedSelections").size());

                StandaloneTestSupport.postJson(client, base2 + "/api/v1/student/responses", resumedJwt, Map.of(
                        "answers", List.of(Map.of(
                                "questionPosition", 6,
                                "selectedOptionIndex", engineKey.get(5).get("correctIndex").asInt(),
                                "clientTimestamp", Instant.now().toString())),
                        "lastQuestionIndex", 6
                ));
                StandaloneTestSupport.postJson(client, base2 + "/api/v1/student/finish", resumedJwt, Map.of("lastQuestionIndex", 6));

                StandaloneTestSupport.postJson(client, base2 + "/api/v1/coe/sessions/" + ctx.sessionId() + "/stop", coe2, Map.of());
                JsonNode results = StandaloneTestSupport.getJson(
                        client, base2 + "/api/v1/admin/sessions/" + ctx.sessionId() + "/results", admin2, null, null);
                assertEquals(6, results.get("results").get(0).get("correctCount").asInt());
                assertEquals(12, results.get("results").get(0).get("marksAwarded").asInt());

                JsonNode auditVerify = StandaloneTestSupport.postJson(client, base2 + "/api/v1/admin/audit/verify", admin2, Map.of());
                assertTrue(auditVerify.get("intact").asBoolean());
            } finally {
                proc2.destroyForcibly();
                proc2.waitFor();
            }
        } finally {
            if (proc1.isAlive()) {
                proc1.destroyForcibly();
                proc1.waitFor();
            }
        }
    }
}
