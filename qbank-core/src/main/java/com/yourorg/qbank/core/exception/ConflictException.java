package com.yourorg.qbank.core.exception;

public class ConflictException extends QBankException {
    public ConflictException(String message) {
        super(409, "CONFLICT", message);
    }
}
