package com.pally.infrastructure.persistence.progress;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserJpaRepository extends JpaRepository<UserJpaEntity, String> {}
