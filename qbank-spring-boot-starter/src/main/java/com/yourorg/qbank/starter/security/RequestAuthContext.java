package com.yourorg.qbank.starter.security;

import com.yourorg.qbank.core.port.AuthPort;

/**
 * Thread-local holder populated per request by the QBank authentication filter
 * and cleared strictly in a finally block on request completion.
 */
public final class RequestAuthContext {

    private static final ThreadLocal<AuthPort.AuthActor> HOLDER = new ThreadLocal<>();

    private RequestAuthContext() {
    }

    public static void set(AuthPort.AuthActor actor) {
        HOLDER.set(actor);
    }

    public static AuthPort.AuthActor get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
