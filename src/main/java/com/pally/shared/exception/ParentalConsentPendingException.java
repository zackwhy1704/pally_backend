package com.pally.shared.exception;

/**
 * A child-data ingress (or any gated action) was blocked because the under-13 user's
 * account is awaiting PARENTAL approval. Distinct from {@code AI_CONSENT_REQUIRED}
 * (third-party transfer) and a plain 403: the client shows the "waiting for your
 * parent" panel with a working resend.
 *
 * <p>Carries display metadata so the blocked user can act: a MASKED parent email
 * (which inbox to check) and resend availability/cooldown. Never the full email.
 * A {@link GuardianRequiredException} subtype so existing catch sites still work; the
 * global handler maps it to code {@code PARENTAL_CONSENT_PENDING}.
 */
public class ParentalConsentPendingException extends GuardianRequiredException {

    public static final String CODE = "PARENTAL_CONSENT_PENDING";

    private final String maskedParentEmail;
    private final boolean resendAvailable;
    private final long resendAvailableInSeconds;

    public ParentalConsentPendingException(String maskedParentEmail,
                                           boolean resendAvailable,
                                           long resendAvailableInSeconds) {
        super(CODE);
        this.maskedParentEmail = maskedParentEmail;
        this.resendAvailable = resendAvailable;
        this.resendAvailableInSeconds = resendAvailableInSeconds;
    }

    public String getMaskedParentEmail() { return maskedParentEmail; }
    public boolean isResendAvailable() { return resendAvailable; }
    public long getResendAvailableInSeconds() { return resendAvailableInSeconds; }
}
