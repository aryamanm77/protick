package com.yourorg.qbank.standalone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yourorg.qbank.core.model.ExamType;
import com.yourorg.qbank.core.model.UserRole;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

final class StandaloneTestSupport {

    static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private StandaloneTestSupport() {
    }

    static KeyPairPaths writeRandomKeys(Path secretsDir) throws IOException {
        Files.createDirectories(secretsDir);
        SecureRandom rnd = new SecureRandom();
        byte[] aes = new byte[32];
        byte[] hmac = new byte[32];
        rnd.nextBytes(aes);
        rnd.nextBytes(hmac);
        Path aesFile = secretsDir.resolve("qbank-aes.key");
        Path hmacFile = secretsDir.resolve("qbank-hmac.key");
        Files.writeString(aesFile, Base64.getEncoder().encodeToString(aes), StandardCharsets.UTF_8);
        Files.writeString(hmacFile, Base64.getEncoder().encodeToString(hmac), StandardCharsets.UTF_8);
        return new KeyPairPaths(aesFile, hmacFile);
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static Process spawnServer(
            int port,
            Path dbPath,
            Path storageDir,
            KeyPairPaths keys,
            String engineToken,
            int loginRateLimit,
            Path logFile
    ) throws IOException {
        String javaBin = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
        String cp = System.getProperty("java.class.path");
        String jdbcUrl = "jdbc:h2:file:" + dbPath.toAbsolutePath().toString().replace('\\', '/')
                + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";

        List<String> cmd = List.of(
                javaBin,
                "-Xmx512m",
                "-cp", cp,
                QBankStandaloneApplication.class.getName(),
                "--server.port=" + port,
                "--server.address=127.0.0.1",
                "--server.ssl.enabled=false",
                "--qbank.base-path=/qbank",
                "--qbank.auth.mode=standalone",
                "--qbank.datasource.url=" + jdbcUrl,
                "--qbank.datasource.username=qbank",
                "--qbank.datasource.password=",
                "--qbank.storage-dir=" + storageDir.toAbsolutePath(),
                "--qbank.security.encryption-key-file=" + keys.aesFile().toAbsolutePath(),
                "--qbank.security.signing-key-file=" + keys.hmacFile().toAbsolutePath(),
                "--qbank.security.login-rate-limit-per-minute=" + loginRateLimit,
                "--qbank.engine.service-token=" + engineToken,
                "--qbank.backup.enabled=false"
        );
        Files.createDirectories(logFile.getParent());
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile());
        return pb.start();
    }

    static Process spawnRestoreCli(
            Path dbPath,
            Path storageDir,
            KeyPairPaths keys,
            Path backupFile,
            Path logFile
    ) throws IOException {
        String javaBin = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
        String cp = System.getProperty("java.class.path");
        String jdbcUrl = "jdbc:h2:file:" + dbPath.toAbsolutePath().toString().replace('\\', '/')
                + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";

        List<String> cmd = List.of(
                javaBin,
                "-Xmx512m",
                "-cp", cp,
                QBankStandaloneApplication.class.getName(),
                "--server.ssl.enabled=false",
                "--qbank.datasource.url=" + jdbcUrl,
                "--qbank.datasource.username=qbank",
                "--qbank.datasource.password=",
                "--qbank.storage-dir=" + storageDir.toAbsolutePath(),
                "--qbank.security.encryption-key-file=" + keys.aesFile().toAbsolutePath(),
                "--qbank.security.signing-key-file=" + keys.hmacFile().toAbsolutePath(),
                "--qbank.restore-file=" + backupFile.toAbsolutePath(),
                "--qbank.restore-confirm=true"
        );
        Files.createDirectories(logFile.getParent());
        return new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();
    }

    static long waitForReady(HttpClient client, int port, Process proc, Path logFile, Duration maxWait) throws Exception {
        long startNs = System.nanoTime();
        long deadlineNs = startNs + maxWait.toNanos();
        URI probeUri = URI.create("http://127.0.0.1:" + port + "/qbank/api/v1/auth/info");
        while (System.nanoTime() < deadlineNs) {
            if (!proc.isAlive()) {
                String logs = Files.exists(logFile) ? Files.readString(logFile) : "";
                throw new IllegalStateException("Standalone child JVM exited early (code=" + proc.exitValue() + "):\n" + logs);
            }
            try {
                HttpRequest req = HttpRequest.newBuilder(probeUri).timeout(Duration.ofSeconds(2)).GET().build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return (System.nanoTime() - startNs) / 1_000_000L;
                }
            } catch (IOException ignored) {
                // server still booting
            }
            Thread.sleep(250);
        }
        String logs = Files.exists(logFile) ? Files.readString(logFile) : "";
        throw new IllegalStateException("Timed out waiting for standalone JVM on port " + port + ":\n" + logs);
    }

    static LiveSessionContext bootstrapLiveSession(HttpClient client, String baseUrl, int studentCount) throws Exception {
        postJson(client, baseUrl + "/api/v1/auth/setup-admin", null, Map.of(
                "username", "admin1", "displayName", "Admin One", "password", "AdminStrong#2026"));
        String adminToken = loginStaff(client, baseUrl, "admin1", "AdminStrong#2026");

        postJson(client, baseUrl + "/api/v1/admin/users", adminToken, Map.of(
                "username", "hod1", "displayName", "HOD CS", "role", UserRole.HOD.name(), "temporaryPassword", "HodStrong#2026"));
        postJson(client, baseUrl + "/api/v1/admin/users", adminToken, Map.of(
                "username", "coe1", "displayName", "COE One", "role", UserRole.COE.name(), "temporaryPassword", "CoeStrong#2026"));
        postJson(client, baseUrl + "/api/v1/admin/users", adminToken, Map.of(
                "username", "inv1", "displayName", "Invigilator One", "role", UserRole.INVIGILATOR.name(), "temporaryPassword", "InvStrong#2026"));

        String hodToken = loginStaff(client, baseUrl, "hod1", "HodStrong#2026");
        String coeToken = loginStaff(client, baseUrl, "coe1", "CoeStrong#2026");
        String invToken = loginStaff(client, baseUrl, "inv1", "InvStrong#2026");

        JsonNode subj = postJson(client, baseUrl + "/api/v1/admin/subjects", adminToken, Map.of(
                "code", "CS201", "name", "Systems Engineering", "expectedQuestionsPerModule", 80, "marksPerQuestion", 2));
        long subjectId = subj.get("id").asLong();

        byte[] workbook = buildValidFiveModuleWorkbook(80);
        JsonNode previews = postMultipart(client, baseUrl + "/api/v1/hod/subjects/" + subjectId + "/import-multi/preview",
                hodToken, "cs201.xlsx", workbook);
        for (int i = 0; i < previews.size(); i++) {
            String pid = previews.get(i).get("previewId").asText();
            postJson(client, baseUrl + "/api/v1/hod/previews/" + pid + "/confirm", hodToken, Map.of());
        }

        Instant now = Instant.now();
        Instant startAt = now.minus(2, ChronoUnit.MINUTES);
        Instant endAt = now.plus(60, ChronoUnit.MINUTES);
        JsonNode gen = postJson(client, baseUrl + "/api/v1/coe/exams/generate", coeToken, Map.ofEntries(
                Map.entry("subjectId", subjectId),
                Map.entry("title", "Internal Assessment Live"),
                Map.entry("examType", ExamType.INTERNAL.name()),
                Map.entry("modules", List.of(1, 2)),
                Map.entry("examDate", LocalDate.now().toString()),
                Map.entry("rotationOffset", 0),
                Map.entry("includeAfternoon", false),
                Map.entry("morningStartAt", startAt.toString()),
                Map.entry("morningEndAt", endAt.toString())
        ));
        long sessionId = gen.get("sessions").get(0).get("session").get("id").asLong();
        int expectedVer = gen.get("sessions").get(0).get("session").get("version").asInt();

        postJson(client, baseUrl + "/api/v1/coe/sessions/" + sessionId + "/approve", coeToken, Map.of(
                "setNumber", 1,
                "expectedVersion", expectedVer,
                "scheduledStartAt", startAt.toString(),
                "scheduledEndAt", endAt.toString()
        ));

        List<Map<String, Object>> roster = new ArrayList<>();
        for (int i = 1; i <= studentCount; i++) {
            roster.add(Map.of(
                    "rollNumber", String.format("R%04d", i),
                    "fullName", "Student " + i,
                    "roomCode", "HALL-1",
                    "rowNumber", (i - 1) / 10 + 1,
                    "seatNumber", (i - 1) % 10 + 1,
                    "fontScale", 1.0,
                    "highContrast", false,
                    "accommodationExtraMinutes", 0
            ));
        }
        JsonNode creds = postJson(client, baseUrl + "/api/v1/coe/sessions/" + sessionId + "/students",
                coeToken, Map.of("students", roster));
        Map<String, String> codes = new LinkedHashMap<>();
        Map<String, String> versions = new LinkedHashMap<>();
        for (int i = 0; i < creds.size(); i++) {
            String roll = creds.get(i).get("rollNumber").asText();
            codes.put(roll, creds.get(i).get("oneTimeAccessCode").asText());
            versions.put(roll, creds.get(i).get("assignedVersion").asText());
        }

        postJson(client, baseUrl + "/api/v1/coe/sessions/" + sessionId + "/start", coeToken, Map.of());
        return new LiveSessionContext(adminToken, hodToken, coeToken, invToken, subjectId, sessionId, codes, versions);
    }

    static String loginStaff(HttpClient client, String baseUrl, String username, String password) throws Exception {
        JsonNode n = postJson(client, baseUrl + "/api/v1/auth/login", null, Map.of("username", username, "password", password));
        return n.get("token").asText();
    }

    static JsonNode getJson(HttpClient client, String url, String bearer, String extraHeaderName, String extraHeaderValue) throws Exception {
        HttpResponse<String> resp = sendRaw(client, "GET", url, bearer, extraHeaderName, extraHeaderValue, null, "application/json");
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("GET " + url + " failed HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return JSON.readTree(resp.body());
    }

    static JsonNode postJson(HttpClient client, String url, String bearer, Object bodyObj) throws Exception {
        HttpResponse<String> resp = sendRaw(client, "POST", url, bearer, null, null,
                JSON.writeValueAsBytes(bodyObj), "application/json");
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("POST " + url + " failed HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(resp.body());
    }

    static JsonNode postMultipart(HttpClient client, String url, String bearer, String filename, byte[] bytes) throws Exception {
        String boundary = "----QBankBoundary" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> resp = sendRaw(client, "POST", url, bearer, null, null,
                out.toByteArray(), "multipart/form-data; boundary=" + boundary);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("Multipart POST " + url + " failed HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return JSON.readTree(resp.body());
    }

    static HttpResponse<String> sendRaw(
            HttpClient client,
            String method,
            String url,
            String bearer,
            String extraHeaderName,
            String extraHeaderVal,
            byte[] body,
            String contentType
    ) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15));
        if (bearer != null && !bearer.isBlank()) {
            b.header("Authorization", "Bearer " + bearer);
        }
        if (extraHeaderName != null && extraHeaderVal != null) {
            b.header(extraHeaderName, extraHeaderVal);
        }
        if ("GET".equals(method)) {
            b.GET();
        } else {
            b.header("Content-Type", contentType);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static byte[] buildValidFiveModuleWorkbook(int questionsPerModule) throws Exception {
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
                for (int q = 1; q <= questionsPerModule; q++) {
                    Row r = s.createRow(q);
                    r.createCell(0).setCellValue("CS201 M" + m + " Q" + q + "?");
                    r.createCell(1).setCellValue("A-" + m + "-" + q);
                    r.createCell(2).setCellValue("B-" + m + "-" + q);
                    r.createCell(3).setCellValue("C-" + m + "-" + q);
                    r.createCell(4).setCellValue("D-" + m + "-" + q);
                    r.createCell(5).setCellValue("C");
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    record KeyPairPaths(Path aesFile, Path hmacFile) {}

    record LiveSessionContext(
            String adminToken,
            String hodToken,
            String coeToken,
            String invToken,
            long subjectId,
            long sessionId,
            Map<String, String> accessCodes,
            Map<String, String> assignedVersions
    ) {}
}
