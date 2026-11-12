package com.yourorg.qbank.starter.web;

import com.yourorg.qbank.core.exception.ConflictException;
import com.yourorg.qbank.core.exception.ForbiddenException;
import com.yourorg.qbank.core.exception.RateLimitException;
import com.yourorg.qbank.core.model.UserAccount;
import com.yourorg.qbank.core.model.UserRole;
import com.yourorg.qbank.core.port.AuthPort;
import com.yourorg.qbank.core.port.ClockPort;
import com.yourorg.qbank.core.port.UserAccountRepository;
import com.yourorg.qbank.core.service.CoreStandaloneAuthService;
import com.yourorg.qbank.starter.config.QBankProperties;
import com.yourorg.qbank.starter.security.IpRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("${qbank.base-path:/qbank}/api/v1/auth")
public class QBankAuthController {

    private final QBankProperties props;
    private final AuthPort authPort;
    private final CoreStandaloneAuthService standaloneAuthService;
    private final UserAccountRepository userAccountRepository;
    private final ClockPort clockPort;
    private final IpRateLimiter loginRateLimiter;
    private final boolean hostModeActive;

    public QBankAuthController(
            QBankProperties props,
            AuthPort authPort,
            CoreStandaloneAuthService standaloneAuthService,
            UserAccountRepository userAccountRepository,
            ClockPort clockPort,
            boolean hostModeActive
    ) {
        this.props = props;
        this.authPort = authPort;
        this.standaloneAuthService = standaloneAuthService;
        this.userAccountRepository = userAccountRepository;
        this.clockPort = clockPort;
        this.hostModeActive = hostModeActive;
        this.loginRateLimiter = new IpRateLimiter(props.getSecurity().getLoginRateLimitPerMinute(), 60L);
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("authMode", hostModeActive ? "host" : "standalone");
        out.put("basePath", props.normalizedBasePath());
        out.put("setupRequired", !hostModeActive && standaloneAuthService != null && standaloneAuthService.isSetupRequired());

        var actorOpt = authPort.currentActor();
        if (actorOpt.isPresent()) {
            var a = actorOpt.get();
            String primaryRole = (a.roles() == null || a.roles().isEmpty())
                    ? "" : a.roles().iterator().next().name();
            boolean mustChange = !hostModeActive && userAccountRepository != null
                    && !a.roles().contains(UserRole.STUDENT)
                    && userAccountRepository.findByUsernameIgnoreCase(a.username())
                    .map(UserAccount::mustChangePassword)
                    .orElse(false);
            out.put("user", Map.of(
                    "username", a.username(),
                    "displayName", a.displayName(),
                    "role", primaryRole,
                    "mustChangePassword", mustChange,
                    "assignedRoomCode", a.assignedRoomCode() == null ? "" : a.assignedRoomCode()
            ));
        } else {
            out.put("user", null);
        }
        return out;
    }

    @PostMapping("/setup-admin")
    public CoreStandaloneAuthService.UserView setupAdmin(@RequestBody SetupAdminRequest req) {
        requireStandalone();
        return standaloneAuthService.setupInitialAdmin(req.username(), req.displayName(), req.password());
    }

    @PostMapping("/login")
    public CoreStandaloneAuthService.LoginResponse login(@RequestBody LoginRequest req, HttpServletRequest http) {
        requireStandalone();
        String clientIp = http.getRemoteAddr() == null ? "unknown" : http.getRemoteAddr();
        if (!loginRateLimiter.tryConsume("staff:" + clientIp, clockPort.now())) {
            throw new RateLimitException("Too many login requests from this host. Please wait 60 seconds.");
        }
        return standaloneAuthService.login(req.username(), req.password());
    }

    @PostMapping("/logout")
    public Map<String, String> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (standaloneAuthService != null && authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            standaloneAuthService.logout(authHeader.substring(7).trim());
        }
        return Map.of("status", "logged_out");
    }

    @PostMapping("/change-password")
    public Map<String, String> changePassword(@RequestBody ChangePasswordRequest req) {
        requireStandalone();
        standaloneAuthService.changeOwnPassword(req.currentPassword(), req.newPassword());
        return Map.of("status", "updated");
    }

    private void requireStandalone() {
        if (hostModeActive || standaloneAuthService == null) {
            throw new ForbiddenException("Standalone authentication endpoints are disabled in host mode");
        }
    }

    public record SetupAdminRequest(@NotBlank String username, @NotBlank String displayName, @NotBlank String password) {}
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}
}
