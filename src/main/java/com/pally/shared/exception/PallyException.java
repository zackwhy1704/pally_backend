package com.pally.shared.exception;

/**
 * Base exception for all Pally domain errors.
 * Extend this for specific business rule violations.
 */
public abstract class PallyException extends RuntimeException {

    private final int httpStatus;

    protected PallyException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    protected PallyException(String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
