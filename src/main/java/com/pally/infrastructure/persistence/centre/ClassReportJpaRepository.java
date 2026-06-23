package com.pally.infrastructure.persistence.centre;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ClassReportJpaRepository
        extends JpaRepository<ClassReportJpaEntity, String> {
}
