package com.pally.shared.exception;

/**
 * Thrown when an under-13 user (per the server-derived birth-year rule) attempts
 * a gated action before a parent/guardian has claimed and consented for them.
 *
 * <p>PDPC 2024: children under 13 cannot self-consent; a parent must approve.
 * Once a parent is linked (the claim flow records parental consent), this gate
 * is satisfied and the child proceeds normally.
 *
 * <p>A distinct subtype of {@link ConsentRequiredException} so the
 * {@code GlobalExceptionHandler} maps it to HTTP 403 with the recognisable code
 * {@code PARENT_LINK_REQUIRED} — letting the Flutter client route to the
 * "ask a grown-up to link your account" screen rather than the AI-disclosure
 * gate ({@code AI_CONSENT_REQUIRED}) or the generic consent gate
 * ({@code CONSENT_REQUIRED}).
 *
 * <p>Maps to HTTP 403 with body:
 * {@code {data:{code:"PARENT_LINK_REQUIRED", reason:"PARENT_LINK_REQUIRED"}, error:..., status:403}}.
 */
public class GuardianRequiredException extends ConsentRequiredException {

    public GuardianRequiredException(String reason) {
        super(reason);
    }
}
