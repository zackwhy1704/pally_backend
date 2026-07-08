package com.pally.api.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request to link a social provider to an existing account (resolves LINK_REQUIRED).
 * Carries the same provider token as sign-in (re-verified server-side) plus the
 * challenge secret: {@code password} for challenge A, {@code code} for challenge B.
 */
public record LinkSocialRequest(
    String idToken,
    String identityToken,
    @NotBlank String provider,
    String password,
    String code
) {}
