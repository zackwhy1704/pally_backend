package com.pally.infrastructure.persistence.mochi;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserMochiJpaRepository
        extends JpaRepository<UserMochiJpaEntity, UserMochiJpaEntity.Id> {

    List<UserMochiJpaEntity> findById_UserId(String userId);
}
