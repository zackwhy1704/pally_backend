package com.pally.infrastructure.persistence.avatar;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AvatarJpaRepository extends JpaRepository<AvatarJpaEntity, String> {

    List<AvatarJpaEntity> findByUserId(String userId);

    boolean existsByIdAndUserId(String id, String userId);

    /// True when this avatar is a centre/class brain, whose quiz results feed
    /// teacher-visible analytics — i.e. a "teacher-graded" quiz whose answer key
    /// must NOT be shipped to the client.
    boolean existsByIdAndCentreAvatarTrue(String id);

    @Query("SELECT a.id FROM AvatarJpaEntity a WHERE a.brainState = :brainState")
    List<String> findIdsByBrainState(@Param("brainState") String brainState);
}
