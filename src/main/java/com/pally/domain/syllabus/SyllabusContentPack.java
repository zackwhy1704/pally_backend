package com.pally.domain.syllabus;

import java.time.Instant;

/**
 * Maps a (syllabusCode, topicTag) to the hidden {@code AvatarKind.SYLLABUS_PACK} avatar
 * that holds that pack's generated modules. syllabusCode/topicTag are internal tags only
 * (e.g. "SG-G3-COMPUTING-7155" / "Abstraction-and-Algorithms") — never shown in
 * user-facing copy, per the existing brand-positioning decision (no visible MOE/SEAB
 * language in the product).
 */
public record SyllabusContentPack(
        String id,
        String syllabusCode,
        String topicTag,
        String avatarId,
        String packStatus,        // PackStatus enum name
        String sourceLicenseNote, // which OER source(s) + license this pack was grounded on
        Instant createdAt
) {
}
