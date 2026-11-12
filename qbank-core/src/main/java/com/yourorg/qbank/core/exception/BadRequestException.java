package com.yourorg.qbank.core.exception;

public class BadRequestException extends QBankException {
    public BadRequestException(String message) {
        super(400, "BAD_REQUEST", message);
    }
}
