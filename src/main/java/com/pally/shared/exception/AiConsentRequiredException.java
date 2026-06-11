package com.pally.shared.exception;

/**
 * Thrown when a user attempts an action that sends their personal data to a
 * third-party overseas AI processor (Anthropic Claude, Google Gemini) before
 * they have granted explicit AI data-transfer consent.
 *
 * <p>This is a distinct subtype of {@link ConsentRequiredException} so the
 * {@code GlobalExceptionHandler} can map it to HTTP 403 with the recognisable
 * code {@code AI_CONSENT_REQUIRED} — letting the Flutter client show the
 * AI-disclosure screen instead of treating it like the parental-consent gate
 * (code {@code CONSENT_REQUIRED}).
 *
 * <p>Maps to HTTP 403 with body:
 * {@code {data:{code:"AI_CONSENT_REQUIRED", reason:"AI_DATA_TRANSFER"}, error:..., status:403}}.
 */
public class AiConsentRequiredException extends ConsentRequiredException {

    public AiConsentRequiredException(String reason) {
        super(reason);
    }
}
