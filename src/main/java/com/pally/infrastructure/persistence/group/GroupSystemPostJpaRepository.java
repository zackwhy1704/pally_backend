package com.pally.infrastructure.persistence.group;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GroupSystemPostJpaRepository
        extends JpaRepository<GroupSystemPostJpaEntity, String> {

    List<GroupSystemPostJpaEntity> findTop50ByGroupIdOrderByCreatedAtDesc(String groupId);
}
