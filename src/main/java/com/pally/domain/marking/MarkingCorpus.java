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
        String subject,   // Subject enum name (e.g. "MATHS")
        String avatarId,
        String scope,     // ownership hedge (V102); always "ORG" today, dormant
        Instant createdAt
) {
    /** The only scope in use today: one marking brain per (org, subject). */
    public static final String SCOPE_ORG = "ORG";
}
