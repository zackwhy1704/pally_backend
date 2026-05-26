package com.pally.api.auth.dto;

public record SocialAuthRequest(
        String idToken,
        String identityToken,
        String authCode
) {}
