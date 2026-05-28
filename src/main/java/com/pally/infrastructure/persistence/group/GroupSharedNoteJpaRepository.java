package com.pally.infrastructure.persistence.group;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GroupSharedNoteJpaRepository
        extends JpaRepository<GroupSharedNoteJpaEntity, String> {

    @Query(value = """
            SELECT * FROM group_shared_notes
            WHERE group_id = :groupId
            ORDER BY shared_at DESC
            LIMIT 50
            """, nativeQuery = true)
    List<GroupSharedNoteJpaEntity> findRecentByGroupId(
            @Param("groupId") String groupId);
}
