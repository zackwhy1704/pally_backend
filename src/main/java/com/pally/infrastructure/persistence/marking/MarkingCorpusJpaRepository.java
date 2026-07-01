package com.pally.infrastructure.persistence.marking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MarkingCorpusJpaRepository
        extends JpaRepository<MarkingCorpusJpaEntity, String> {

    Optional<MarkingCorpusJpaEntity> findByOrgIdAndSubject(String orgId, String subject);
}
