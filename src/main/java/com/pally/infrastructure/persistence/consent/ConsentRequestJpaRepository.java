package com.pally.infrastructure.persistence.consent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConsentRequestJpaRepository
        extends JpaRepository<ConsentRequestJpaEntity, String> {

    Optional<ConsentRequestJpaEntity> findByToken(String token);

    List<ConsentRequestJpaEntity> findByChildUserIdOrderByCreatedAtDesc(String childUserId);

    Optional<ConsentRequestJpaEntity> findFirstByChildUserIdAndStatusOrderByCreatedAtDesc(
            String childUserId, String status);
}
