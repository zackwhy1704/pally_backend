package com.pally.api.auth.dto;

public record AuthResponse(
        String userId,
        String token,
        boolean isNewUser,
        boolean setupComplete
) {}
