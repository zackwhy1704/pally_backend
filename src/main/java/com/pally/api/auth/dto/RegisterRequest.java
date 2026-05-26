package com.pally.api.auth.dto;

public record RegisterRequest(
        String email,
        String password,
        String displayName
) {}
