package com.yourorg.qbank.core.exception;

public class RateLimitException extends QBankException {
    public RateLimitException(String message) {
        super(429, "RATE_LIMITED", message);
    }
}
