package com.yourorg.qbank.core.exception;

/**
 * Base runtime exception for domain/business/security errors in qbank-core.
 */
public abstract class QBankException extends RuntimeException {

    private final int statusCode;
    private final String code;

    protected QBankException(int statusCode, String code, String message) {
        super(message);
        this.statusCode = statusCode;
        this.code = code;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getCode() {
        return code;
    }
}
