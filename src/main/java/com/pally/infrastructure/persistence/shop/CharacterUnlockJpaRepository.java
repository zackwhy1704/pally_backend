package com.pally.infrastructure.persistence.shop;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CharacterUnlockJpaRepository extends JpaRepository<CharacterUnlockJpaEntity, String> {
    List<CharacterUnlockJpaEntity> findByUserId(String userId);
    boolean existsByUserIdAndCharacter(String userId, String character);
}
