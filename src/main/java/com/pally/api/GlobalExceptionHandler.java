package com.pally.api;

import com.pally.shared.exception.AiConsentRequiredException;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.exception.ConsentRequiredException;
import com.pally.shared.exception.DuplicateContentException;
import com.pally.shared.exception.GuardianRequiredException;
import com.pally.shared.exception.LinkRequiredException;
import com.pally.shared.exception.ParentalConsentPendingException;
import com.pally.shared.exception.ProfileCompletionRequiredException;
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

    /// Half-elevated (PENDING_CONSENT) block: distinct code PARENTAL_CONSENT_PENDING
    /// with the MASKED parent email + resend metadata so the client renders the
    /// "waiting for your parent — resend" panel. Declared as a more-specific subtype of
    /// GuardianRequiredException; Spring routes to this handler for the rich case.
    @ExceptionHandler(ParentalConsentPendingException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleParentalConsentPending(
            ParentalConsentPendingException ex) {
        log.debug("Parental consent pending (masked={})", ex.getMaskedParentEmail());
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("code", ParentalConsentPendingException.CODE);
        payload.put("reason", ex.getReason());
        payload.put("parentEmailMasked", ex.getMaskedParentEmail());
        payload.put("resendAvailable", ex.isResendAvailable());
        payload.put("resendAvailableInSeconds", ex.getResendAvailableInSeconds());
        payload.put("message",
                "Your account is waiting for your parent to approve it. Ask them to check "
                + "their email and tap the approval link.");
        return ResponseEntity.status(403)
                .body(new ApiResponse<>(payload, ex.getMessage(), 403));
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

    /// Social-signup profile not finished (PENDING_PROFILE): distinct code
    /// {@code PROFILE_COMPLETION_REQUIRED} so the client routes to the DOB step. Declared
    /// before {@link #handleConsentRequired} so this more specific subtype wins.
    @ExceptionHandler(ProfileCompletionRequiredException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleProfileCompletionRequired(
            ProfileCompletionRequiredException ex) {
        log.debug("Profile completion required: {}", ex.getReason());
        Map<String, Object> payload = Map.of(
                "code", ProfileCompletionRequiredException.CODE,
                "reason", ex.getReason());
        return ResponseEntity.status(403).body(new ApiResponse<>(payload, ex.getMessage(), 403));
    }

    /// Social sign-in whose verified email matches an existing DIFFERENT-credential
    /// account: 409 LINK_REQUIRED with the challenge kind + provider, so the client can
    /// run explicit linking (never a silent auto-link — the takeover vector).
    @ExceptionHandler(LinkRequiredException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleLinkRequired(
            LinkRequiredException ex) {
        log.debug("Link required challenge={} provider={}", ex.getChallenge(), ex.getProvider());
        Map<String, Object> payload = Map.of(
                "code", LinkRequiredException.CODE,
                "challenge", ex.getChallenge(),
                "provider", ex.getProvider());
        return ResponseEntity.status(409).body(new ApiResponse<>(payload, ex.getMessage(), 409));
    }

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

    /// A too-large multipart upload fails during request PARSING — before the
    /// controller's own size check — so without this it falls through to a generic
    /// 500. Map it to a clean 413 the client can show as "file too large".
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUpload(
            org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        log.warn("Upload rejected — exceeds multipart size limit: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.error("File is too large (max 25MB).", 413));
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
     * Persistence integrity violations — value-too-long (e.g. a grade label longer
     * than its column), bad enums, constraint breaches — are CLIENT input errors,
     * not server faults. Map to 400 so over-long/invalid input never 500s. (This is
     * exactly how a too-narrow grade_level column silently 500'd avatar creation.)
     * The DB message can leak schema, so we return a generic one and log the cause.
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(
            org.springframework.dao.DataIntegrityViolationException ex) {
        // Log the PRECISE failing column/constraint, not the generic umbrella
        // message — "too long" (sqlState 22001) and "violated a constraint"
        // (23502 NOT NULL / 23505 UNIQUE / 23503 FK) are different fixes, and
        // Postgres names the exact table/column/constraint in the wrapped cause.
        // Schema detail stays server-side; the client still gets the generic msg.
        log.warn("Data integrity violation (mapped to 400): {}", describeIntegrityCause(ex));
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(
                        "Invalid input — a value was too long or violated a constraint.", 400));
    }

    /// Unwrap a {@link org.springframework.dao.DataIntegrityViolationException} to the
    /// underlying {@link java.sql.SQLException} so the log names exactly what broke.
    /// The 5-char sqlState discriminates the fix (22001 value-too-long vs 23502 NOT NULL
    /// vs 23503 FK vs 23505 UNIQUE), and the Postgres driver's message text already
    /// carries the constraint name + offending key detail. Uses only JDBC types (the pg
    /// driver is runtimeOnly, not on the compile classpath).
    private String describeIntegrityCause(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException se) {
                String state = se.getSQLState();
                String kind = switch (state == null ? "" : state) {
                    case "22001" -> "VALUE_TOO_LONG";
                    case "23502" -> "NOT_NULL_VIOLATION";
                    case "23503" -> "FK_VIOLATION";
                    case "23505" -> "UNIQUE_VIOLATION";
                    case "22021" -> "INVALID_UTF8";
                    default -> "OTHER";
                };
                return "sqlState=" + state + " kind=" + kind + " cause=" + se.getMessage();
            }
        }
        return (ex instanceof org.springframework.dao.DataIntegrityViolationException dive)
                ? dive.getMostSpecificCause().getMessage() : ex.getMessage();
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
