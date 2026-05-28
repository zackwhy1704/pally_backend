package com.pally.infrastructure.persistence.group;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StudyGroupJpaRepository
        extends JpaRepository<StudyGroupJpaEntity, String> {

    Optional<StudyGroupJpaEntity> findByInviteCode(String inviteCode);

    /// Returns every group the given user is a member of (any role).
    @Query(value = """
            SELECT g.* FROM study_groups g
            INNER JOIN group_members m ON m.group_id = g.id
            WHERE m.user_id = :userId
            ORDER BY g.created_at DESC
            """, nativeQuery = true)
    List<StudyGroupJpaEntity> findGroupsForUser(@Param("userId") String userId);

    boolean existsByInviteCode(String inviteCode);
}
