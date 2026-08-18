package com.pally.infrastructure.persistence.syllabus;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SyllabusContentPackAliasJpaRepository
        extends JpaRepository<SyllabusContentPackAliasJpaEntity, String> {

    List<SyllabusContentPackAliasJpaEntity> findBySyllabusCode(String syllabusCode);

    List<SyllabusContentPackAliasJpaEntity> findByPackId(String packId);
}
