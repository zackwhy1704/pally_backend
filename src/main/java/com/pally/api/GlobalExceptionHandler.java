package com.pally.api;

import com.pally.shared.exception.AiConsentRequiredException;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.exception.ConsentRequiredException;
import com.pally.shared.exception.DuplicateContentException;
import com.pally.shared.exception.GuardianRequiredException;
import com.pally.shared.exception.OcrUnavailableException;
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

    /// Under-13 guardian gate. Distinct code {@code PARENT_LINK_REQUIRED} so the
    /// Flutter client routes the child to the "ask a grown-up to link your account"
    /// screen rather than the AI-disclosure or generic consent gate. Declared
    /// before the other consent handlers so this more specific subtype wins.
    @ExceptionHandler(GuardianRequiredException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleGuardianRequired(
            GuardianRequiredException ex) {
        log.debug("Guardian (parent-link) required: {}", ex.getReason());
        Map<String, Object> payload = Map.of(
                "code", "PARENT_LINK_REQUIRED",
                "reason", ex.getReason());
        return ResponseEntity
                .status(403)
                .body(new ApiResponse<>(payload, ex.getMessage(), 403));
    }

    /// AI data-transfer consent gate. Distinct code {@code AI_CONSENT_REQUIRED}
    /// (not the parental {@code CONSENT_REQUIRED}) so the Flutter client routes to
    /// the AI-disclosure screen rather than the parent-approval gate. Declared
    /// before {@link #handleConsentRequired} so the more specific subtype wins.
    @ExceptionHandler(AiConsentRequiredException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleAiConsentRequired(
            AiConsentRequiredException ex) {
        log.debug("AI consent required: {}", ex.getReason());
        Map<String, Object> payload = Map.of(
                "code", "AI_CONSENT_REQUIRED",
                "reason", ex.getReason());
        return ResponseEntity
                .status(403)
                .body(new ApiResponse<>(payload, ex.getMessage(), 403));
    }

    /// Mirrors the UPGRADE_REQUIRED handler — routes the Flutter client to
    /// the consent-gate sheet instead of a raw 403 error.
    @ExceptionHandler(ConsentRequiredException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleConsentRequired(
            ConsentRequiredException ex) {
        log.debug("Consent required: {}", ex.getReason());
        Map<String, Object> payload = Map.of(
                "code", "CONSENT_REQUIRED",
                "reason", ex.getReason());
        return ResponseEntity
                .status(403)
                .body(new ApiResponse<>(payload, ex.getMessage(), 403));
    }

    /// Duplicate/similar content: structured 409 so the Flutter client can
    /// show a specific "already uploaded" message instead of a generic error.
    @ExceptionHandler(DuplicateContentException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleDuplicateContent(
            DuplicateContentException ex) {
        log.debug("Duplicate content kind={} existing={}", ex.getKind(), ex.getExistingFileName());
        Map<String, Object> payload = Map.of(
                "code", ex.getKind() == DuplicateContentException.Kind.EXACT
                        ? "DUPLICATE_FILE"
                        : "SIMILAR_CONTENT",
                "existingFileName", ex.getExistingFileName(),
                "similarity", ex.getSimilarity());
        return ResponseEntity
                .status(409)
                .body(new ApiResponse<>(payload, ex.getMessage(), 409));
    }

    /// Public review link that is no longer PENDING → 410 Gone with the
    /// terminal review status in the body so the web client can render the
    /// right "already reviewed / expired / revoked" message.
    @ExceptionHandler(com.pally.domain.review.ReviewRequestService.GoneException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleReviewGone(
            com.pally.domain.review.ReviewRequestService.GoneException ex) {
        log.debug("Review link gone status={}", ex.getReviewStatus());
        Map<String, Object> payload = Map.of("status", ex.getReviewStatus());
        return ResponseEntity.status(410)
                .body(new ApiResponse<>(payload, ex.getMessage(), 410));
    }

    /// Safety-net for any {@link OcrUnavailableException} that escapes use-case
    /// catch blocks. Upload use cases catch it first and convert to UploadResult.Failure;
    /// this 422 covers any other call site that doesn't.
    @ExceptionHandler(OcrUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleOcrUnavailable(OcrUnavailableException ex) {
        log.warn("OCR unavailable: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.error(
                        "Couldn't extract text from this file — our image reading service is temporarily "
                        + "unavailable. Please try again shortly.", 422));
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

    /// Handles {@code @Validated} constraint failures on path/query
    /// params (different exception type than {@code @Valid} on bodies).
    /// Same envelope so the client's PallyError mapper can treat them
    /// identically.
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
            jakarta.validation.ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("; "));
        log.debug("Constraint violation: {}", message);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(message, 400));
    }

    /**
     * Unmapped routes → clean 404 in the standard envelope instead of falling
     * through to the generic 500 catch-all below. Requires
     * {@code spring.mvc.throw-exception-if-no-handler-found=true} and
     * {@code spring.web.resources.add-mappings=false}. Leaks only method + path,
     * never a stack trace.
     */
    @ExceptionHandler(org.springframework.web.servlet.NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoHandler(
            org.springframework.web.servlet.NoHandlerFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(
                        "No endpoint " + ex.getHttpMethod() + " " + ex.getRequestURL(), 404));
    }

    /**
     * Date/number parse failures and other illegal arguments are CLIENT input
     * errors, not server faults — map to 400 instead of falling through to the
     * generic 500 below. (e.g. a date-only dueDate reaching {@code Instant.parse}.)
     */
    @ExceptionHandler({ java.time.format.DateTimeParseException.class, IllegalArgumentException.class })
    public ResponseEntity<ApiResponse<Void>> handleBadInput(Exception ex) {
        log.debug("Bad input: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("Invalid input: " + ex.getMessage(), 400));
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
