package com.pally.shared.response;

import java.time.Instant;
import java.util.List;

/**
 * Detailed error response for validation failures and structured errors.
 */
public record ErrorResponse(
        int status,
        String message,
        List<FieldError> fieldErrors,
        Instant timestamp
) {

    public record FieldError(String field, String message) {}

    public static ErrorResponse of(int status, String message) {
        return new ErrorResponse(status, message, List.of(), Instant.now());
    }

    public static ErrorResponse ofValidation(int status, String message, List<FieldError> fieldErrors) {
        return new ErrorResponse(status, message, fieldErrors, Instant.now());
    }
}
