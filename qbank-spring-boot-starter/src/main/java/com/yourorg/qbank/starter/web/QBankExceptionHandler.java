package com.yourorg.qbank.starter.web;

import com.yourorg.qbank.core.exception.QBankException;
import com.yourorg.qbank.starter.service.QBankHealthMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

@RestControllerAdvice(basePackages = "com.yourorg.qbank.starter.web")
public class QBankExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(QBankExceptionHandler.class);
    private final QBankHealthMonitor healthMonitor;

    public QBankExceptionHandler(QBankHealthMonitor healthMonitor) {
        this.healthMonitor = healthMonitor;
    }

    @ExceptionHandler(QBankException.class)
    public ResponseEntity<Map<String, Object>> handleQBank(QBankException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of(
                        "code", ex.getCode(),
                        "message", ex.getMessage() == null ? ex.getCode() : ex.getMessage()
                ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArg(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "BAD_REQUEST", "message", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("Invalid request payload");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "VALIDATION_ERROR", "message", msg));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUpload(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "FILE_TOO_LARGE", "message", "Uploaded file exceeds maximum allowed size"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        healthMonitor.recordServerError();
        log.error("Unhandled QBank endpoint error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", "INTERNAL_ERROR", "message", "Unexpected server error"));
    }
}
