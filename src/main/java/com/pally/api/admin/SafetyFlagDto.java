package com.pally.api.admin;

import java.time.Instant;

public record SafetyFlagDto(
        String id,
        String category,
        String severity,
        String snippet,
        String source,
        Instant createdAt,
        String messageId,
        String avatarId
) {}
