package com.pally.domain.assignment;

import java.math.BigDecimal;

/**
 * Domain port for recording content-gap signals.
 *
 * <p>A signal is emitted when a student's query falls below the closed-book
 * relevance threshold for a centre-class module — indicating the corpus may
 * not cover the question. The implementation (JPA adapter) lives in
 * {@code infrastructure/persistence/assignment}.
 */
public interface ContentGapSignalRepository {

    /**
     * Persists one content-gap signal.
     *
     * @param avatarId          the centre avatar that served the refusal (may be null if not applicable)
     * @param classId           the class the avatar belongs to
     * @param userId            the student who asked
     * @param queryText         the raw query (truncated to 1 000 chars by caller)
     * @param matchedConfidence the best keyword-overlap score achieved (0.0 – 1.0)
     */
    void recordSignal(String avatarId, String classId, String userId,
                      String queryText, BigDecimal matchedConfidence);
}
