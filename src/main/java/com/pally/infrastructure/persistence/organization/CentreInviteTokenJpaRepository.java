package com.pally.infrastructure.persistence.organization;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CentreInviteTokenJpaRepository
        extends JpaRepository<CentreInviteTokenJpaEntity, String> {

    List<CentreInviteTokenJpaEntity> findAllByOrderByCreatedAtDesc();
}
