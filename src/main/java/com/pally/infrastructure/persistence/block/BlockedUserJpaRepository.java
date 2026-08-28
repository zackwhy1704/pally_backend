package com.pally.infrastructure.persistence.block;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BlockedUserJpaRepository extends JpaRepository<BlockedUserJpaEntity, String> {

    List<BlockedUserJpaEntity> findByBlockerUserId(String blockerUserId);

    boolean existsByBlockerUserIdAndBlockedUserId(String blockerUserId, String blockedUserId);

    @Modifying
    @Query("DELETE FROM BlockedUserJpaEntity b "
            + "WHERE b.blockerUserId = :blocker AND b.blockedUserId = :blocked")
    int deletePair(@Param("blocker") String blocker, @Param("blocked") String blocked);
}
