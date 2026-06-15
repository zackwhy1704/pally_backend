package com.pally.domain.curriculum;

import java.time.Instant;

/**
 * Domain type representing a syllabus curriculum.
 * No JPA annotations — persistence is handled by the adapter.
 */
public record Curriculum(
        String id,
        String name,
        String subject,
        String grade,
        String ownerUserId,
        boolean isDefault,
        Instant createdAt) {}
