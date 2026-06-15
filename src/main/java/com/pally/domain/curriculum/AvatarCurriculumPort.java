package com.pally.domain.curriculum;

import java.util.Optional;

/**
 * Domain port for curriculum attachment operations on avatars.
 * Keeps the curriculum module decoupled from the avatar aggregate.
 */
public interface AvatarCurriculumPort {

    /**
     * Returns the owner user-id for the given avatar, or empty if not found.
     */
    Optional<String> findOwnerUserId(String avatarId);

    /**
     * Attaches or detaches a curriculum from an avatar.
     * Pass {@code null} for {@code curriculumId} to detach.
     * Returns the new (possibly null) curriculum id stored on the avatar.
     */
    String attachCurriculum(String avatarId, String curriculumId);
}
