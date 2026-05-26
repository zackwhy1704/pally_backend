package com.pally.api.auth.dto;

public record LoginRequest(
        String email,
        String password
) {}
