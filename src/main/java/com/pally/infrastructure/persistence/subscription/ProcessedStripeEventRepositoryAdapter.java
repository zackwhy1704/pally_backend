package com.pally.infrastructure.persistence.subscription;

import com.pally.domain.subscription.ProcessedStripeEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * JPA adapter that satisfies the {@link ProcessedStripeEventRepository} domain port.
 */
@Component
@RequiredArgsConstructor
public class ProcessedStripeEventRepositoryAdapter implements ProcessedStripeEventRepository {

    private final ProcessedStripeEventJpaRepository jpa;

    @Override
    public boolean claimEvent(String eventId, String eventType, Instant processedAt) {
        try {
            ProcessedStripeEventJpaEntity row = new ProcessedStripeEventJpaEntity();
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
