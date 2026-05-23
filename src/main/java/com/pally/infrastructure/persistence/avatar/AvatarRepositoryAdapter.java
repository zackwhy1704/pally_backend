package com.pally.infrastructure.persistence.avatar;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Adapter that implements the domain port using JPA.
 * {@code @Transactional} lives here, not in use cases.
 */
@Component
@RequiredArgsConstructor
public class AvatarRepositoryAdapter implements AvatarRepository {

    private final AvatarJpaRepository jpaRepository;

    @Override
    @Transactional
    public Avatar save(Avatar avatar) {
        AvatarJpaEntity entity = AvatarJpaEntity.fromDomain(avatar);
        return jpaRepository.save(entity).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Avatar> findById(String id) {
        return jpaRepository.findById(id).map(AvatarJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Avatar> findByUserId(String userId) {
        return jpaRepository.findByUserId(userId).stream()
                .map(AvatarJpaEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByIdAndUserId(String id, String userId) {
        return jpaRepository.existsByIdAndUserId(id, userId);
    }

    @Override
    @Transactional
    public void deleteById(String id) {
        jpaRepository.deleteById(id);
    }
}
