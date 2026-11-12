package com.yourorg.qbank.starter.security;

import com.yourorg.qbank.core.model.UserRole;
import com.yourorg.qbank.core.port.AuthPort;
import com.yourorg.qbank.starter.config.QBankProperties;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.*;

/**
 * Isolated Spring Security bridge loaded ONLY when spring-security is on the host classpath.
 */
public class HostSecurityBridge implements AuthPort {

    private final QBankProperties.Roles roleConfig;

    public HostSecurityBridge(QBankProperties.Roles roleConfig) {
        this.roleConfig = roleConfig;
    }

    @Override
    public Optional<AuthPort.AuthActor> currentActor() {
        return resolveFromSecurityContext();
    }

    public Optional<AuthPort.AuthActor> resolveFromSecurityContext() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return Optional.empty();
        }
        Set<UserRole> mapped = EnumSet.noneOf(UserRole.class);
        String roomCode = null;
        Set<String> adminNames = splitNames(roleConfig.getAdmin());
        Set<String> hodNames = splitNames(roleConfig.getHod());
        Set<String> coeNames = splitNames(roleConfig.getCoe());
        Set<String> invNames = splitNames(roleConfig.getInvigilator());
        String roomPrefix = roleConfig.getRoomAuthorityPrefix();

        for (GrantedAuthority ga : auth.getAuthorities()) {
            String name = ga.getAuthority();
            if (name == null) continue;
            if (adminNames.contains(name)) mapped.add(UserRole.ADMIN);
            if (hodNames.contains(name)) mapped.add(UserRole.HOD);
            if (coeNames.contains(name)) mapped.add(UserRole.COE);
            if (invNames.contains(name)) mapped.add(UserRole.INVIGILATOR);
            if (roomPrefix != null && !roomPrefix.isBlank() && name.startsWith(roomPrefix)) {
                roomCode = name.substring(roomPrefix.length()).trim();
            }
        }
        if (mapped.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AuthPort.AuthActor(
                auth.getName(),
                auth.getName(),
                Collections.unmodifiableSet(mapped),
                null,
                null,
                roomCode
        ));
    }

    private static Set<String> splitNames(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        Set<String> set = new HashSet<>();
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) set.add(t);
        }
        return set;
    }
}
