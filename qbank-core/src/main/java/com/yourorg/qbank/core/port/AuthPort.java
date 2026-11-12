package com.yourorg.qbank.core.port;

import com.yourorg.qbank.core.model.UserRole;

import java.util.Optional;
import java.util.Set;

/**
 * Port for host-provided or standalone authentication and role verification.
 * In embedded mode, qbank delegates user identity and role ownership to the host application.
 */
public interface AuthPort {

    Optional<AuthActor> currentActor();

    default boolean hasRole(UserRole role) {
        if (role == null) {
            return false;
        }
        return currentActor()
                .map(actor -> actor.roles() != null && actor.roles().contains(role))
                .orElse(false);
    }

    record AuthActor(
            String username,
            String displayName,
            Set<UserRole> roles,
            Long studentSessionId,
            String studentAssignedVersion,
            String assignedRoomCode
    ) {
        public AuthActor(String username, String displayName, Set<UserRole> roles) {
            this(username, displayName, roles, null, null, null);
        }
    }
}
