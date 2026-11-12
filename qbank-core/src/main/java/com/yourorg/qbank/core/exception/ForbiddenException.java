package com.yourorg.qbank.core.exception;

public class ForbiddenException extends QBankException {
    public ForbiddenException(String message) {
        super(403, "FORBIDDEN", message);
    }

    public ForbiddenException(String code, String message) {
        super(403, code, message);
    }
}
