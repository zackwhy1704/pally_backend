package com.pally.api.auth.dto;

public record SetupRequest(
        String childName,
        Integer yearLevel,
        String curriculum
) {}
