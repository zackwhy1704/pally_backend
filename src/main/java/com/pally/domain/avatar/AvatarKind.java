package com.pally.domain.avatar;

/**
 * Classifies an avatar by its product role.
 *
 * <p>This split is what keeps the centre's per-class avatars (unbounded, one per
 * class) from ever polluting the student collectible economy (the classic + ATW
 * characters earned via XP, stars, and the mystery box).
 *
 * <ul>
 *   <li>{@link #PERSONAL} — a student's own collectible tutor. The default for
 *       every avatar created through the student API. Eligible for the shop /
 *       collection / mystery-box economy.</li>
 *   <li>{@link #CENTRE_CLASS} — a class-bound avatar created by the centre when a
 *       class is provisioned (the hidden class corpus avatar and the branded
 *       closed-book avatars handed to each enrolled student). Rendered with a
 *       server-derived "class uniform" appearance and invisible to the economy.
 *       Students may not rename or delete these.</li>
 *   <li>{@link #MARKING_CORPUS} — a hidden, teacher-facing brain holding a
 *       centre's compiled MARKING STANDARD, keyed by (orgId, subject). Created
 *       lazily when a teacher first uploads a marking reference for a subject.
 *       Never student-visible, never in the economy, never in student
 *       {@code listAvatars}. Its knowledge files run through the SAME wiki
 *       harness (incremental compile, merge-into-existing, conflict, decay) so
 *       the marking standard improves per upload exactly like the student
 *       notes-wiki.</li>
 * </ul>
 */
public enum AvatarKind {
    PERSONAL,
    CENTRE_CLASS,
    MARKING_CORPUS
}
