package com.pally.domain.syllabus;

/**
 * Pack-level visibility gate for {@link SyllabusContentPack} — orthogonal to, and never
 * a substitute for, the existing item-level {@code ModuleContentItemRepository.SERVABLE_STATUSES}
 * gate. A pack only ever appears in "browse starter content" when it is PUBLISHED
 * <em>and</em> has at least one item that is independently LIVE/APPROVED.
 */
public enum PackStatus {
    /** Freshly created / mid-generation. Never shown to students. */
    DRAFT,
    /** Explicitly published by a platform admin. Eligible to appear in browse, subject to
     *  its items' own servable status. */
    PUBLISHED,
    /** Withdrawn. Never shown to students, regardless of item status. */
    ARCHIVED
}
