package com.pally.infrastructure.persistence.group;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GroupMemberJpaRepository
        extends JpaRepository<GroupMemberJpaEntity, GroupMemberJpaEntity.PK> {

    List<GroupMemberJpaEntity> findByGroupId(String groupId);

    long countByUserId(String userId);

    boolean existsByGroupIdAndUserId(String groupId, String userId);

    void deleteByGroupIdAndUserId(String groupId, String userId);
}
