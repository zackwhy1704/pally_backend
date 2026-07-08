package com.pally.infrastructure.persistence.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AuthChallengeJpaRepository extends JpaRepository<AuthChallengeJpaEntity, String> {

    Optional<AuthChallengeJpaEntity> findByCodeHashAndPurposeAndStatus(
            String codeHash, String purpose, String status);

    List<AuthChallengeJpaEntity> findByUserIdAndPurposeAndStatus(
            String userId, String purpose, String status);
}
