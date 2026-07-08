package com.pally.shared.exception;

/**
 * A social sign-in whose VERIFIED email matches an existing account of a DIFFERENT
 * credential type. We must NOT auto-link (that silent auto-link was the account-takeover
 * vector) and must NOT create a duplicate — the caller has to prove ownership through an
 * explicit challenge first. Maps to HTTP 409 with code {@code LINK_REQUIRED}, carrying
 * only the challenge kind the client needs (never enumerating the account further).
 */
public class LinkRequiredException extends PallyException {

    public static final String CODE = "LINK_REQUIRED";

    /** "PASSWORD" — existing account has a password; "EMAIL_CODE" — passwordless. */
    private final String challenge;
    /** Which sign-in the user is trying to link: "google" | "apple". */
    private final String provider;

    public LinkRequiredException(String challenge, String provider) {
        super("An account with this email already exists — verify it to link " + provider
                + " sign-in", 409);
        this.challenge = challenge;
        this.provider = provider;
    }

    public String getChallenge() { return challenge; }
    public String getProvider() { return provider; }
}
