package com.pally.infrastructure.persistence.subscription;

import com.pally.domain.subscription.ProcessedRevenueCatEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * JPA adapter satisfying the {@link ProcessedRevenueCatEventRepository} domain
 * port. Mirrors {@link ProcessedStripeEventRepositoryAdapter}.
 */
@Component
@RequiredArgsConstructor
public class ProcessedRevenueCatEventRepositoryAdapter
        implements ProcessedRevenueCatEventRepository {

    private final ProcessedRevenueCatEventJpaRepository jpa;

    @Override
    public boolean claimEvent(String eventId, String eventType, Instant processedAt) {
        try {
            ProcessedRevenueCatEventJpaEntity row = new ProcessedRevenueCatEventJpaEntity();
            row.setEventId(eventId);
            row.setEventType(eventType == null ? "unknown" : eventType);
            row.setProcessedAt(processedAt != null ? processedAt : Instant.now());
            jpa.saveAndFlush(row);
            return true;
        } catch (DataIntegrityViolationException dup) {
            return false;
        }
    }

    @Override
    public void releaseEvent(String eventId) {
        jpa.deleteById(eventId);
    }
}
