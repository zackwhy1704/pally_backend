package com.pally.shared.exception;

public class RelevanceCheckException extends PallyException {

    public RelevanceCheckException(String message) {
        super(message, 422);
    }

    public RelevanceCheckException(String message, Throwable cause) {
        super(message, 422, cause);
    }
}
