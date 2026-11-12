package com.yourorg.qbank.core.exception;

public class UnauthorizedException extends QBankException {
    public UnauthorizedException(String message) {
        super(401, "UNAUTHORIZED", message);
    }
}
