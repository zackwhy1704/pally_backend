package com.pally.infrastructure.persistence.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CompileStatusJpaRepository extends JpaRepository<CompileStatusJpaEntity, String> {
}
