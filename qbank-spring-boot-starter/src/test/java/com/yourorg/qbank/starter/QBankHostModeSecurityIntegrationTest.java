package com.yourorg.qbank.starter;

import com.yourorg.qbank.starter.testhost.TestSecuredHostApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TestSecuredHostApp.class)
@AutoConfigureMockMvc
class QBankHostModeSecurityIntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void hostProps(DynamicPropertyRegistry reg) throws Exception {
        Files.createDirectories(tempDir.resolve("db"));
        Files.createDirectories(tempDir.resolve("storage"));

        SecureRandom rnd = new SecureRandom();
        byte[] aes = new byte[32];
        byte[] hmac = new byte[32];
        rnd.nextBytes(aes);
        rnd.nextBytes(hmac);

        String aesB64 = Base64.getEncoder().encodeToString(aes);
        String hmacB64 = Base64.getEncoder().encodeToString(hmac);

        reg.add("qbank.auth.mode", () -> "auto");
        reg.add("qbank.roles.admin", () -> "HOST_SYS_ADMIN");
        reg.add("qbank.roles.hod", () -> "HOST_DEPT_HEAD");
        reg.add("qbank.roles.coe", () -> "HOST_CONTROLLER");
        reg.add("qbank.roles.invigilator", () -> "HOST_PROCTOR");
        reg.add("qbank.roles.room-authority-prefix", () -> "HALL:");
        reg.add("qbank.datasource.url", () -> "jdbc:h2:file:" + tempDir.resolve("db/host-test").toAbsolutePath().toString().replace('\\', '/') + ";DB_CLOSE_DELAY=-1");
        reg.add("qbank.datasource.username", () -> "sa");
        reg.add("qbank.datasource.password", () -> "");
        reg.add("qbank.storage-dir", () -> tempDir.resolve("storage").toAbsolutePath().toString());
        reg.add("qbank.security.encryption-key-base64", () -> aesB64);
        reg.add("qbank.security.signing-key-base64", () -> hmacB64);
        reg.add("qbank.backup.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void autoDetectsHostModeAndBlocksStandaloneAuthRoutes() throws Exception {
        mvc.perform(get("/qbank/api/v1/auth/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authMode").value("host"))
                .andExpect(jsonPath("$.setupRequired").value(false));
    }

    @Test
    void mapsCustomHostAuthoritiesToHodRoleCleanly() throws Exception {
        String basic = basicAuth("dept.head", "host-pass-1");
        mvc.perform(get("/qbank/api/v1/hod/template").header("Authorization", basic))
                .andExpect(status().isOk());
        mvc.perform(get("/qbank/api/v1/coe/sessions").header("Authorization", basic))
                .andExpect(status().isForbidden());
        mvc.perform(post("/qbank/api/v1/auth/login")
                        .header("Authorization", basic)
                        .contentType("application/json")
                        .content("{\"username\":\"x\",\"password\":\"y\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void mapsHostRoomPrefixAuthorityCleanly() throws Exception {
        String basic = basicAuth("proctor.hall1", "host-pass-2");
        mvc.perform(get("/qbank/api/v1/auth/info").header("Authorization", basic))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("INVIGILATOR"))
                .andExpect(jsonPath("$.user.assignedRoomCode").value("HALL-204"));
    }

    private static String basicAuth(String user, String pass) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }
}
