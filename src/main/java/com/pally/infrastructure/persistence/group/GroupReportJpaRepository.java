package com.pally.infrastructure.persistence.group;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GroupReportJpaRepository
        extends JpaRepository<GroupReportJpaEntity, String> {

    List<GroupReportJpaEntity> findByGroupIdOrderByCreatedAtDesc(String groupId);
}
