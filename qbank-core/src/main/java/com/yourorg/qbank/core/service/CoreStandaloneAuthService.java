package com.yourorg.qbank.core.service;

import com.yourorg.qbank.core.exception.BadRequestException;
import com.yourorg.qbank.core.exception.ConflictException;
import com.yourorg.qbank.core.exception.ForbiddenException;
import com.yourorg.qbank.core.exception.NotFoundException;
import com.yourorg.qbank.core.exception.RateLimitException;
import com.yourorg.qbank.core.exception.UnauthorizedException;
import com.yourorg.qbank.core.model.UserAccount;
import com.yourorg.qbank.core.model.UserRole;
import com.yourorg.qbank.core.model.UserSessionToken;
import com.yourorg.qbank.core.port.AuditPort;
import com.yourorg.qbank.core.port.AuthPort;
import com.yourorg.qbank.core.port.ClockPort;
import com.yourorg.qbank.core.port.CryptoPort;
import com.yourorg.qbank.core.port.JsonCodecPort;
import com.yourorg.qbank.core.port.PasswordHasherPort;
import com.yourorg.qbank.core.port.RandomPort;
import com.yourorg.qbank.core.port.TransactionPort;
import com.yourorg.qbank.core.port.UserAccountRepository;
import com.yourorg.qbank.core.port.UserSessionRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Standalone user, BCrypt credential, lockout rate-limiting, first-run setup wizard,
 * and session token service. Disabled cleanly when host application owns authentication.
 */
public class CoreStandaloneAuthService {

    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final int LOCKOUT_MINUTES = 15;
    public static final int SESSION_TTL_HOURS = 12;
    public static final int MIN_PASSWORD_LENGTH = 8;

    private final UserAccountRepository userRepo;
    private final UserSessionRepository sessionRepo;
    private final PasswordHasherPort passwordHasher;
    private final CryptoPort cryptoPort;
    private final RandomPort randomPort;
    private final ClockPort clockPort;
    private final TransactionPort transactionPort;
    private final AuditPort auditPort;
    private final JsonCodecPort jsonCodec;
    private final AuthGuard authGuard;

    public CoreStandaloneAuthService(
            UserAccountRepository userRepo,
            UserSessionRepository sessionRepo,
            PasswordHasherPort passwordHasher,
            CryptoPort cryptoPort,
            RandomPort randomPort,
            ClockPort clockPort,
            TransactionPort transactionPort,
            AuditPort auditPort,
            JsonCodecPort jsonCodec,
            AuthPort authPort
    ) {
        this.userRepo = Objects.requireNonNull(userRepo);
        this.sessionRepo = Objects.requireNonNull(sessionRepo);
        this.passwordHasher = Objects.requireNonNull(passwordHasher);
        this.cryptoPort = Objects.requireNonNull(cryptoPort);
        this.randomPort = Objects.requireNonNull(randomPort);
        this.clockPort = Objects.requireNonNull(clockPort);
        this.transactionPort = Objects.requireNonNull(transactionPort);
        this.auditPort = Objects.requireNonNull(auditPort);
        this.jsonCodec = Objects.requireNonNull(jsonCodec);
        this.authGuard = new AuthGuard(authPort);
    }

    public boolean isSetupRequired() {
        return !userRepo.existsByRole(UserRole.ADMIN);
    }

    public UserView setupInitialAdmin(String rawUsername, String rawDisplayName, String rawPassword) {
        String username = normalizeUsername(rawUsername);
        String displayName = normalizeDisplayName(rawDisplayName);
        validatePassword(rawPassword);

        return transactionPort.inTransaction(() -> {
            if (userRepo.existsByRole(UserRole.ADMIN)) {
                throw new ConflictException("Initial setup already completed");
            }
            Instant now = clockPort.now();
            UserAccount saved = userRepo.save(new UserAccount(
                    null,
                    username,
                    displayName,
                    UserRole.ADMIN,
                    passwordHasher.hash(rawPassword),
                    true,
                    false,
                    0,
                    null,
                    now,
                    now,
                    0L
            ));
            auditPort.append(
                    "INITIAL_ADMIN_CREATED",
                    saved.username(),
                    UserRole.ADMIN.name(),
                    "USER",
                    String.valueOf(saved.id()),
                    "{}"
            );
            return toView(saved);
        });
    }

    public LoginResponse login(String rawUsername, String rawPassword) {
        if (rawUsername == null || rawUsername.isBlank() || rawPassword == null || rawPassword.isBlank()) {
            throw new BadRequestException("Username and password are required");
        }
        String username = rawUsername.trim().toLowerCase(Locale.ROOT);
        UserAccount user = userRepo.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new UnauthorizedException("Invalid username or password"));

        if (!user.active()) {
            throw new ForbiddenException("Account is disabled");
        }
        Instant now = clockPort.now();
        if (user.lockedUntil() != null && now.isBefore(user.lockedUntil())) {
            throw new RateLimitException("Account temporarily locked after failed logins until " + user.lockedUntil());
        }

        if (!passwordHasher.matches(rawPassword, user.passwordHash())) {
            int nextFails = user.failedLoginAttempts() + 1;
            Instant lockUntil = nextFails >= MAX_FAILED_ATTEMPTS ? now.plus(LOCKOUT_MINUTES, ChronoUnit.MINUTES) : null;
            userRepo.save(new UserAccount(
                    user.id(), user.username(), user.displayName(), user.role(), user.passwordHash(),
                    user.active(), user.mustChangePassword(), nextFails, lockUntil,
                    user.createdAt(), now, user.version()
            ));
            auditPort.append(
                    "LOGIN_FAILED",
                    user.username(),
                    user.role().name(),
                    "USER",
                    String.valueOf(user.id()),
                    jsonCodec.toJson(Map.of("failedAttempts", nextFails, "locked", lockUntil != null))
            );
            if (lockUntil != null) {
                throw new RateLimitException("Too many failed login attempts. Locked until " + lockUntil);
            }
            throw new UnauthorizedException("Invalid username or password");
        }

        UserAccount resetUser = userRepo.save(new UserAccount(
                user.id(), user.username(), user.displayName(), user.role(), user.passwordHash(),
                user.active(), user.mustChangePassword(), 0, null,
                user.createdAt(), now, user.version()
        ));

        String rawToken = randomPort.randomUuid() + "." + Long.toUnsignedString(randomPort.nextLong(), 36);
        String tokenHash = cryptoPort.sha256Hex(rawToken);
        Instant expiresAt = now.plus(SESSION_TTL_HOURS, ChronoUnit.HOURS);

        sessionRepo.save(new UserSessionToken(
                tokenHash,
                resetUser.id(),
                resetUser.username(),
                resetUser.role(),
                now,
                expiresAt
        ));

        auditPort.append(
                "LOGIN_SUCCESS",
                resetUser.username(),
                resetUser.role().name(),
                "USER",
                String.valueOf(resetUser.id()),
                "{}"
        );
        return new LoginResponse(rawToken, expiresAt, toView(resetUser));
    }

    public Optional<UserView> authenticateSessionToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String tokenHash = cryptoPort.sha256Hex(rawToken.trim());
        Optional<UserSessionToken> optToken = sessionRepo.findByTokenHash(tokenHash);
        if (optToken.isEmpty()) {
            return Optional.empty();
        }
        UserSessionToken st = optToken.get();
        Instant now = clockPort.now();
        if (!st.expiresAt().isAfter(now)) {
            sessionRepo.deleteByTokenHash(tokenHash);
            return Optional.empty();
        }
        Optional<UserAccount> userOpt = userRepo.findById(st.userId());
        if (userOpt.isEmpty() || !userOpt.get().active()) {
            return Optional.empty();
        }
        return Optional.of(toView(userOpt.get()));
    }

    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        String tokenHash = cryptoPort.sha256Hex(rawToken.trim());
        sessionRepo.findByTokenHash(tokenHash).ifPresent(st -> {
            sessionRepo.deleteByTokenHash(tokenHash);
            auditPort.append(
                    "LOGOUT",
                    st.username(),
                    st.role().name(),
                    "USER",
                    String.valueOf(st.userId()),
                    "{}"
            );
        });
    }

    public void changeOwnPassword(String currentPassword, String newPassword) {
        AuthPort.AuthActor actor = authGuard.requireAuthenticated();
        if (currentPassword == null || currentPassword.isBlank()) {
            throw new BadRequestException("Current password is required");
        }
        validatePassword(newPassword);

        UserAccount user = userRepo.findByUsernameIgnoreCase(actor.username())
                .orElseThrow(() -> new NotFoundException("User account not found"));
        if (!passwordHasher.matches(currentPassword, user.passwordHash())) {
            throw new UnauthorizedException("Current password does not match");
        }
        Instant now = clockPort.now();
        userRepo.save(new UserAccount(
                user.id(), user.username(), user.displayName(), user.role(),
                passwordHasher.hash(newPassword),
                user.active(), false, 0, null,
                user.createdAt(), now, user.version()
        ));
        auditPort.append(
                "PASSWORD_CHANGED",
                user.username(),
                user.role().name(),
                "USER",
                String.valueOf(user.id()),
                "{}"
        );
    }

    public UserView createUser(String rawUsername, String rawDisplayName, UserRole role, String tempPassword) {
        AuthPort.AuthActor actor = authGuard.requireRole(UserRole.ADMIN);
        if (role == null || role == UserRole.STUDENT) {
            throw new BadRequestException("Staff user role must be ADMIN, HOD, COE, or INVIGILATOR");
        }
        String username = normalizeUsername(rawUsername);
        String displayName = normalizeDisplayName(rawDisplayName);
        validatePassword(tempPassword);

        if (userRepo.findByUsernameIgnoreCase(username).isPresent()) {
            throw new ConflictException("Username already exists: " + username);
        }

        Instant now = clockPort.now();
        UserAccount saved = userRepo.save(new UserAccount(
                null,
                username,
                displayName,
                role,
                passwordHasher.hash(tempPassword),
                true,
                true,
                0,
                null,
                now,
                now,
                0L
        ));
        auditPort.append(
                "USER_CREATED",
                actor.username(),
                AuthGuard.primaryRoleName(actor),
                "USER",
                String.valueOf(saved.id()),
                jsonCodec.toJson(Map.of("createdUsername", saved.username(), "role", role.name()))
        );
        return toView(saved);
    }

    public List<UserView> listUsers() {
        authGuard.requireRole(UserRole.ADMIN);
        return userRepo.findAll().stream().map(CoreStandaloneAuthService::toView).toList();
    }

    public UserView updateUserStatus(long userId, boolean active, boolean clearLockout) {
        AuthPort.AuthActor actor = authGuard.requireRole(UserRole.ADMIN);
        UserAccount target = userRepo.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
        Instant now = clockPort.now();

        UserAccount saved = userRepo.save(new UserAccount(
                target.id(),
                target.username(),
                target.displayName(),
                target.role(),
                target.passwordHash(),
                active,
                target.mustChangePassword(),
                clearLockout ? 0 : target.failedLoginAttempts(),
                clearLockout ? null : target.lockedUntil(),
                target.createdAt(),
                now,
                target.version()
        ));
        if (!active) {
            sessionRepo.deleteByUserId(userId);
        }
        auditPort.append(
                "USER_STATUS_UPDATED",
                actor.username(),
                AuthGuard.primaryRoleName(actor),
                "USER",
                String.valueOf(userId),
                jsonCodec.toJson(Map.of("active", active, "clearLockout", clearLockout))
        );
        return toView(saved);
    }

    private static UserView toView(UserAccount u) {
        return new UserView(
                u.id(), u.username(), u.displayName(), u.role(),
                u.active(), u.mustChangePassword(), u.failedLoginAttempts(), u.lockedUntil()
        );
    }

    private static String normalizeUsername(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("Username is required");
        }
        String clean = raw.trim().toLowerCase(Locale.ROOT);
        if (!clean.matches("[a-z0-9._-]{3,40}")) {
            throw new BadRequestException("Username must be 3-40 chars (a-z, 0-9, ., _, -)");
        }
        return clean;
    }

    private static String normalizeDisplayName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("Display name is required");
        }
        return raw.trim();
    }

    private static void validatePassword(String pw) {
        if (pw == null || pw.length() < MIN_PASSWORD_LENGTH) {
            throw new BadRequestException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }

    public record UserView(
            Long id,
            String username,
            String displayName,
            UserRole role,
            boolean active,
            boolean mustChangePassword,
            int failedLoginAttempts,
            Instant lockedUntil
    ) {
    }

    public record LoginResponse(
            String token,
            Instant expiresAt,
            UserView user
    ) {
    }
}
