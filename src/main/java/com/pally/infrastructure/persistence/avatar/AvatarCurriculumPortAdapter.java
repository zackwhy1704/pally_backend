package com.pally.infrastructure.persistence.avatar;

import com.pally.domain.curriculum.AvatarCurriculumPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * JPA adapter implementing {@link AvatarCurriculumPort}.
 * Operates directly on the avatar row to read/write the curriculum_id column,
 * keeping the domain {@code Avatar} type free of curriculum concerns.
 */
@Component
@RequiredArgsConstructor
public class AvatarCurriculumPortAdapter implements AvatarCurriculumPort {

    private final AvatarJpaRepository jpa;

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findOwnerUserId(String avatarId) {
        return jpa.findById(avatarId).map(AvatarJpaEntity::getUserId);
    }

    @Override
    @Transactional
    public String attachCurriculum(String avatarId, String curriculumId) {
        AvatarJpaEntity entity = jpa.findById(avatarId).orElseThrow();
        entity.setCurriculumId(curriculumId);
        jpa.save(entity);
        return entity.getCurriculumId();
    }
}
