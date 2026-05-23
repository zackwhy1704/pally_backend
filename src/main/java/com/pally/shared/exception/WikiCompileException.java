package com.pally.shared.exception;

public class WikiCompileException extends PallyException {

    public WikiCompileException(String message) {
        super(message, 500);
    }

    public WikiCompileException(String message, Throwable cause) {
        super(message, 500, cause);
    }
}
