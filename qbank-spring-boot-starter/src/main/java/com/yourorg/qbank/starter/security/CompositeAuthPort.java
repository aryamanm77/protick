package com.yourorg.qbank.starter.security;

import com.yourorg.qbank.core.port.AuthPort;

import java.util.Optional;

/**
 * AuthPort delegating first to per-request context (student JWT / standalone bearer token),
 * and falling back to an optional host security bridge in embedded mode.
 */
public class CompositeAuthPort implements AuthPort {

    private final AuthPort hostBridge;

    public CompositeAuthPort(AuthPort hostBridge) {
        this.hostBridge = hostBridge;
    }

    @Override
    public Optional<AuthActor> currentActor() {
        AuthActor reqActor = RequestAuthContext.get();
        if (reqActor != null) {
            return Optional.of(reqActor);
        }
        if (hostBridge != null) {
            return hostBridge.currentActor();
        }
        return Optional.empty();
    }
}
