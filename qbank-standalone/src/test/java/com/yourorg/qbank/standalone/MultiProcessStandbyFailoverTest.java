package com.yourorg.qbank.standalone;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies offline CLI encrypted restore + standby server promotion continuity
 * across separate primary and standby JVM processes and database files.
 */
class MultiProcessStandbyFailoverTest {

    @TempDir
    Path tempDir;

    @Test
    void promotesStandbyFromEncryptedSnapshotAndServesUninterruptedWithZeroDataLoss() throws Exception {
        StandaloneTestSupport.KeyPairPaths keys = StandaloneTestSupport.writeRandomKeys(tempDir.resolve("secrets"));
        Path primaryDb = tempDir.resolve("primary/data/qbank-primary");
        Path primaryStorage = tempDir.resolve("primary/storage");
        Path standbyDb = tempDir.resolve("standby/data/qbank-standby");
        Path standbyStorage = tempDir.resolve("standby/storage");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String engineToken = "failover-engine-token";

        int primaryPort = StandaloneTestSupport.freePort();
        Path primaryLog = tempDir.resolve("logs/primary.log");
        Process primaryProc = StandaloneTestSupport.spawnServer(
                primaryPort, primaryDb, primaryStorage, keys, engineToken, 100, primaryLog);

        try {
            StandaloneTestSupport.waitForReady(client, primaryPort, primaryProc, primaryLog, Duration.ofSeconds(60));
            String primaryBase = "http://127.0.0.1:" + primaryPort + "/qbank";

            var ctx = StandaloneTestSupport.bootstrapLiveSession(client, primaryBase, 1);
            String roll = "R0001";
            String code = ctx.accessCodes().get(roll);
            String version = ctx.assignedVersions().get(roll);

            JsonNode engineKey = StandaloneTestSupport.getJson(
                    client, primaryBase + "/api/v1/engine/sessions/" + ctx.sessionId() + "/versions/" + version + "/key",
                    null, "X-QBank-Engine-Token", engineToken);

            JsonNode stuLogin = StandaloneTestSupport.postJson(client, primaryBase + "/api/v1/student/login", null, Map.of(
                    "sessionId", ctx.sessionId(), "rollNumber", roll, "accessCode", code));
            String stuJwt = stuLogin.get("signedToken").asText();

            // Commit Q1 & Q2 answers on Primary
            StandaloneTestSupport.postJson(client, primaryBase + "/api/v1/student/responses", stuJwt, Map.of(
                    "answers", List.of(
                            Map.of("questionPosition", 1, "selectedOptionIndex", engineKey.get(0).get("correctIndex").asInt(), "clientTimestamp", Instant.now().toString()),
                            Map.of("questionPosition", 2, "selectedOptionIndex", engineKey.get(1).get("correctIndex").asInt(), "clientTimestamp", Instant.now().toString())
                    ),
                    "lastQuestionIndex", 2
            ));

            // Capture encrypted backup snapshot from Primary and download over HTTP
            JsonNode backupSummary = StandaloneTestSupport.postJson(
                    client, primaryBase + "/api/v1/admin/backups?tag=failover", ctx.adminToken(), Map.of());
            String backupName = backupSummary.get("filename").asText();

            HttpResponse<byte[]> download = client.send(
                    HttpRequest.newBuilder(URI.create(primaryBase + "/api/v1/admin/backups/" + backupName))
                            .header("Authorization", "Bearer " + ctx.adminToken())
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, download.statusCode());

            Path transferredBackup = tempDir.resolve("standby/" + backupName);
            Files.createDirectories(transferredBackup.getParent());
            Files.write(transferredBackup, download.body());

            // Simulate primary node loss
            primaryProc.destroyForcibly();
            primaryProc.waitFor();

            // Restore encrypted snapshot to Standby DB via offline restore CLI runner & promote Standby JVM
            long failoverStartNs = System.nanoTime();
            Process restoreCli = StandaloneTestSupport.spawnRestoreCli(
                    standbyDb, standbyStorage, keys, transferredBackup, tempDir.resolve("logs/restore-cli.log"));
            int restoreExit = restoreCli.waitFor();
            assertEquals(0, restoreExit, "Offline restore CLI failed: " + Files.readString(tempDir.resolve("logs/restore-cli.log")));

            int standbyPort = StandaloneTestSupport.freePort();
            Path standbyLog = tempDir.resolve("logs/standby.log");
            Process standbyProc = StandaloneTestSupport.spawnServer(
                    standbyPort, standbyDb, standbyStorage, keys, engineToken, 100, standbyLog);
            try {
                StandaloneTestSupport.waitForReady(client, standbyPort, standbyProc, standbyLog, Duration.ofSeconds(90));
                long failoverMs = (System.nanoTime() - failoverStartNs) / 1_000_000L;
                assertTrue(failoverMs < 120_000L, "Failover RTO exceeded 2 minutes: " + failoverMs + "ms");

                String standbyBase = "http://127.0.0.1:" + standbyPort + "/qbank";
                String standbyInv = StandaloneTestSupport.loginStaff(client, standbyBase, "inv1", "InvStrong#2026");
                String standbyCoe = StandaloneTestSupport.loginStaff(client, standbyBase, "coe1", "CoeStrong#2026");
                String standbyAdmin = StandaloneTestSupport.loginStaff(client, standbyBase, "admin1", "AdminStrong#2026");

                // Student resets & resumes on promoted Standby seamlessly
                StandaloneTestSupport.postJson(client,
                        standbyBase + "/api/v1/invigilator/sessions/" + ctx.sessionId() + "/students/" + roll + "/reset-login",
                        standbyInv, Map.of("reason", "Failover to standby node"));

                JsonNode resumed = StandaloneTestSupport.postJson(client, standbyBase + "/api/v1/student/login", null, Map.of(
                        "sessionId", ctx.sessionId(), "rollNumber", roll, "accessCode", code));
                String resumedJwt = resumed.get("signedToken").asText();
                JsonNode paper = StandaloneTestSupport.getJson(client, standbyBase + "/api/v1/student/paper", resumedJwt, null, null);
                assertEquals(2, paper.get("savedSelections").size());

                StandaloneTestSupport.postJson(client, standbyBase + "/api/v1/student/finish", resumedJwt, Map.of("lastQuestionIndex", 2));
                StandaloneTestSupport.postJson(client, standbyBase + "/api/v1/coe/sessions/" + ctx.sessionId() + "/stop", standbyCoe, Map.of());

                JsonNode results = StandaloneTestSupport.getJson(
                        client, standbyBase + "/api/v1/admin/sessions/" + ctx.sessionId() + "/results", standbyAdmin, null, null);
                assertEquals(2, results.get("results").get(0).get("correctCount").asInt());
                assertEquals(4, results.get("results").get(0).get("marksAwarded").asInt());

                JsonNode auditCheck = StandaloneTestSupport.postJson(client, standbyBase + "/api/v1/admin/audit/verify", standbyAdmin, Map.of());
                assertTrue(auditCheck.get("intact").asBoolean());
            } finally {
                standbyProc.destroyForcibly();
                standbyProc.waitFor();
            }
        } finally {
            if (primaryProc.isAlive()) {
                primaryProc.destroyForcibly();
                primaryProc.waitFor();
            }
        }
    }
}
