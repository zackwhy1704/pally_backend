package com.pally.shared.exception;

/**
 * A social-signup account still in {@code PENDING_PROFILE} attempted a gated action
 * before completing its profile (the birth-year step). A distinct
 * {@link ConsentRequiredException} subtype so the {@code GlobalExceptionHandler} maps it
 * to HTTP 403 with code {@code PROFILE_COMPLETION_REQUIRED} — the Flutter client routes
 * to the DOB step rather than the parental-consent or AI-disclosure gates.
 */
public class ProfileCompletionRequiredException extends ConsentRequiredException {

    public static final String CODE = "PROFILE_COMPLETION_REQUIRED";

    public ProfileCompletionRequiredException() {
        super(CODE);
    }
}
