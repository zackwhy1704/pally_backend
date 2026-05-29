package com.pally.infrastructure.persistence.mochi;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MochiCharacterJpaRepository
        extends JpaRepository<MochiCharacterJpaEntity, String> {

    List<MochiCharacterJpaEntity> findByAcquisition(String acquisition);

    List<MochiCharacterJpaEntity> findByThemeId(String themeId);
}
