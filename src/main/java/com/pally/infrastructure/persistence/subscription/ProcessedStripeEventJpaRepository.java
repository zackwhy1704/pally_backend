package com.pally.infrastructure.persistence.subscription;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedStripeEventJpaRepository
        extends JpaRepository<ProcessedStripeEventJpaEntity, String> {
}
