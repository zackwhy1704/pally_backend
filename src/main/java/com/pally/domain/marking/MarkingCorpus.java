package com.pally.domain.marking;

import java.time.Instant;

/**
 * Maps a centre's (orgId, subject) to the {@code MARKING_CORPUS} avatar that
 * holds that subject's compiled marking standard. One marking brain per
 * (org, subject) — all the org's teachers of a subject share and improve it.
 */
public record MarkingCorpus(
        String id,
        String orgId,
        String subject,   // Subject enum name (e.g. "MATH")
        String avatarId,
        Instant createdAt
) {}
