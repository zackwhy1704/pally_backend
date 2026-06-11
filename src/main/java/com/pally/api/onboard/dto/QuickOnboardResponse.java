package com.pally.api.onboard.dto;

public record QuickOnboardResponse(
        String token,
        String userId,
        String avatarId
) {}
