package com.pally.infrastructure.persistence.badge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserBadgeJpaRepository extends JpaRepository<UserBadgeJpaEntity, String> {
    boolean existsByUserIdAndBadgeType(String userId, String badgeType);
    List<UserBadgeJpaEntity> findByUserId(String userId);
}
