package com.yourorg.qbank.standalone;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simulates 1.5x peak exam concurrency (120 concurrent students for an 80-seat exam hall)
 * submitting logins, papers, multi-wave autosaves, heartbeats, and invigilator polls.
 */
class PeakLoadSimulationTest {

    @TempDir
    Path tempDir;

    @Test
    void handles120ConcurrentStudentsWithZero5xxAnd100PercentPersistedResponses() throws Exception {
        int concurrentStudents = 120; // 1.5x an 80-seat hall
        StandaloneTestSupport.KeyPairPaths keys = StandaloneTestSupport.writeRandomKeys(tempDir.resolve("secrets"));
        Path dbFile = tempDir.resolve("data/qbank-load-db");
        Path storageDir = tempDir.resolve("data/storage");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(Executors.newFixedThreadPool(32))
                .build();

        int port = StandaloneTestSupport.freePort();
        Path logFile = tempDir.resolve("logs/load.log");
        Process proc = StandaloneTestSupport.spawnServer(port, dbFile, storageDir, keys, "load-token", 2000, logFile);

        try {
            StandaloneTestSupport.waitForReady(client, port, proc, logFile, Duration.ofSeconds(60));
            String baseUrl = "http://127.0.0.1:" + port + "/qbank";

            var ctx = StandaloneTestSupport.bootstrapLiveSession(client, baseUrl, concurrentStudents);

            ExecutorService pool = Executors.newFixedThreadPool(48);
            CountDownLatch startGate = new CountDownLatch(1);
            List<Future<Long>> futures = new ArrayList<>();
            AtomicInteger unexpectedErrors = new AtomicInteger();

            for (int i = 1; i <= concurrentStudents; i++) {
                String roll = String.format("R%04d", i);
                String code = ctx.accessCodes().get(roll);
                futures.add(pool.submit(() -> {
                    startGate.await();
                    long t0 = System.nanoTime();
                    JsonNode login = StandaloneTestSupport.postJson(client, baseUrl + "/api/v1/student/login", null, Map.of(
                            "sessionId", ctx.sessionId(), "rollNumber", roll, "accessCode", code));
                    String jwt = login.get("signedToken").asText();

                    JsonNode paper = StandaloneTestSupport.getJson(client, baseUrl + "/api/v1/student/paper", jwt, null, null);
                    assertFalse(paper.toString().contains("correctIndex"));

                    List<Map<String, Object>> wave1 = new ArrayList<>();
                    for (int q = 1; q <= 5; q++) {
                        wave1.add(Map.of("questionPosition", q, "selectedOptionIndex", q % 4, "clientTimestamp", Instant.now().toString()));
                    }
                    StandaloneTestSupport.postJson(client, baseUrl + "/api/v1/student/responses", jwt,
                            Map.of("answers", wave1, "lastQuestionIndex", 4));

                    StandaloneTestSupport.postJson(client, baseUrl + "/api/v1/student/heartbeat", jwt,
                            Map.of("currentQuestionIndex", 5));

                    List<Map<String, Object>> wave2 = new ArrayList<>();
                    for (int q = 6; q <= 10; q++) {
                        wave2.add(Map.of("questionPosition", q, "selectedOptionIndex", q % 4, "clientTimestamp", Instant.now().toString()));
                    }
                    StandaloneTestSupport.postJson(client, baseUrl + "/api/v1/student/responses", jwt,
                            Map.of("answers", wave2, "lastQuestionIndex", 9));

                    HttpResponse<String> hb2 = StandaloneTestSupport.sendRaw(client, "POST",
                            baseUrl + "/api/v1/student/heartbeat", jwt, null, null,
                            "{\"currentQuestionIndex\":10}".getBytes(), "application/json");
                    if (hb2.statusCode() >= 500) {
                        unexpectedErrors.incrementAndGet();
                    }
                    return (System.nanoTime() - t0) / 1_000_000L;
                }));
            }

            // Also simulate concurrent invigilator dashboard polling while students hammer autosave
            Future<?> invPoller = pool.submit(() -> {
                try {
                    startGate.await();
                    for (int p = 0; p < 5; p++) {
                        JsonNode mon = StandaloneTestSupport.getJson(
                                client, baseUrl + "/api/v1/invigilator/sessions/" + ctx.sessionId() + "/monitor?roomCode=HALL-1",
                                ctx.invToken(), null, null);
                        assertEquals(concurrentStudents, mon.get("totalStudents").asInt());
                        Thread.sleep(200);
                    }
                } catch (Exception e) {
                    unexpectedErrors.incrementAndGet();
                }
            });

            startGate.countDown();
            List<Long> durationsMs = new ArrayList<>();
            for (Future<Long> f : futures) {
                durationsMs.add(f.get(90, TimeUnit.SECONDS));
            }
            invPoller.get(30, TimeUnit.SECONDS);
            pool.shutdownNow();

            Collections.sort(durationsMs);
            long p95Ms = durationsMs.get((int) Math.floor(durationsMs.size() * 0.95));
            System.out.println("PeakLoad 120-student flow p95 end-to-end latency = " + p95Ms + " ms, max = " + durationsMs.get(durationsMs.size() - 1) + " ms");

            assertEquals(0, unexpectedErrors.get(), "Zero 5xx/errors expected under 1.5x load");

            JsonNode health = StandaloneTestSupport.getJson(client, baseUrl + "/api/v1/admin/health", ctx.adminToken(), null, null);
            assertEquals("UP", health.get("dbStatus").asText());
            assertEquals(0L, health.get("serverErrorsCount").asLong());

            JsonNode finalMon = StandaloneTestSupport.getJson(
                    client, baseUrl + "/api/v1/invigilator/sessions/" + ctx.sessionId() + "/monitor?roomCode=HALL-1",
                    ctx.invToken(), null, null);
            assertEquals(concurrentStudents, finalMon.get("activeCount").asInt());
            int totalSavedAnswers = 0;
            for (JsonNode st : finalMon.get("students")) {
                totalSavedAnswers += st.get("answeredCount").asInt();
            }
            assertEquals(concurrentStudents * 10, totalSavedAnswers, "Every single student autosave must persist durably");
        } finally {
            proc.destroyForcibly();
            proc.waitFor();
        }
    }
}
