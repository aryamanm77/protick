package com.yourorg.qbank.core.exception;

public class NotFoundException extends QBankException {
    public NotFoundException(String message) {
        super(404, "NOT_FOUND", message);
    }
}
