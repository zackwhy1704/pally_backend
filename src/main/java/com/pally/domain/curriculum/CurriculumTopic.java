package com.pally.domain.curriculum;

import java.time.Instant;

/**
 * Domain type representing a single topic inside a {@link Curriculum}.
 * No JPA annotations — persistence is handled by the adapter.
 */
public record CurriculumTopic(
        String id,
        String curriculumId,
        String name,
        String slug,
        int sequence,
        Instant createdAt) {}
