package com.pally.shared.exception;

public class BusinessException extends PallyException {
    public BusinessException(String message, int status) {
        super(message, status);
    }
}
