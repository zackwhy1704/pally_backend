package com.pally.api;

import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.exception.PallyException;
import com.pally.shared.exception.UpgradeRequiredException;
import com.pally.shared.response.ApiResponse;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Global exception handler for all REST controllers.
 *
 * <p>Maps domain and framework exceptions to standardised {@link ApiResponse} error bodies.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Handles 404 Not Found for avatar lookups.
     */
    @ExceptionHandler(AvatarNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleAvatarNotFound(AvatarNotFoundException ex) {
        log.debug("Avatar not found: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage(), 404));
    }

    /// More specific than {@link #handlePallyException} — gives the
    /// frontend a structured payload it can pattern-match on to route
    /// straight to the paywall (instead of just toasting "402").
    @ExceptionHandler(UpgradeRequiredException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleUpgradeRequired(
            UpgradeRequiredException ex) {
        log.debug("Upgrade required: {}", ex.getFeature());
        Map<String, Object> payload = Map.of(
                "code", "UPGRADE_REQUIRED",
                "feature", ex.getFeature());
        return ResponseEntity
                .status(402)
                .body(new ApiResponse<>(payload, ex.getMessage(), 402));
    }

    /**
     * Handles any {@link PallyException} using its embedded HTTP status code.
     */
    @ExceptionHandler(PallyException.class)
    public ResponseEntity<ApiResponse<Void>> handlePallyException(PallyException ex) {
        log.warn("Domain exception: {}", ex.getMessage());
        return ResponseEntity
                .status(ex.getHttpStatus())
                .body(ApiResponse.error(ex.getMessage(), ex.getHttpStatus()));
    }

    /**
     * Handles malformed request bodies (e.g. invalid enum values).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequestBody(
            HttpMessageNotReadableException ex) {
        log.warn("Bad request body: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(
                        "Invalid request body: " + ex.getMostSpecificCause().getMessage(), 400));
    }

    /**
     * Handles validation failures from {@code @Valid} annotations.
     * Joins all field errors into a single readable message.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.debug("Validation failed: {}", message);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(message, 400));
    }

    /**
     * Catch-all handler for unexpected exceptions.
     * Logs the full stack trace but returns only a generic message to the client.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred", 500));
    }
}
