package com.yourorg.qbank.core.service;

import com.yourorg.qbank.core.exception.ForbiddenException;
import com.yourorg.qbank.core.exception.UnauthorizedException;
import com.yourorg.qbank.core.model.UserRole;
import com.yourorg.qbank.core.port.AuthPort;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Central server-side role authorization helper invoked at the entry of every core service method.
 */
public final class AuthGuard {

    private final AuthPort authPort;

    public AuthGuard(AuthPort authPort) {
        this.authPort = Objects.requireNonNull(authPort, "authPort must not be null");
    }

    public AuthPort.AuthActor requireAuthenticated() {
        return authPort.currentActor()
                .orElseThrow(() -> new UnauthorizedException("Authentication required"));
    }

    public AuthPort.AuthActor requireRole(UserRole requiredRole) {
        AuthPort.AuthActor actor = requireAuthenticated();
        if (actor.roles() == null || !actor.roles().contains(requiredRole)) {
            throw new ForbiddenException("Role " + requiredRole + " required");
        }
        return actor;
    }

    public AuthPort.AuthActor requireAnyRole(UserRole first, UserRole... rest) {
        AuthPort.AuthActor actor = requireAuthenticated();
        Set<UserRole> allowed = EnumSet.of(first, rest);
        if (actor.roles() == null || actor.roles().stream().noneMatch(allowed::contains)) {
            throw new ForbiddenException("Insufficient permissions for this operation");
        }
        return actor;
    }

    public static String primaryRoleName(AuthPort.AuthActor actor) {
        if (actor == null || actor.roles() == null || actor.roles().isEmpty()) {
            return "ANONYMOUS";
        }
        return actor.roles().iterator().next().name();
    }
}
