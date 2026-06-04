package com.pally.infrastructure.persistence.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface BiometricChallengeJpaRepository extends JpaRepository<BiometricChallengeJpaEntity, String> {
    Optional<BiometricChallengeJpaEntity> findByChallengeHashAndUsedFalse(String challengeHash);
    List<BiometricChallengeJpaEntity> findByUserId(String userId);
}
