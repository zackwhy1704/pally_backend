package com.pally.domain.knowledge.usecase;

/**
 * A wiki page that could not be persisted during a compile run, with the reason
 * (the most-specific cause — typically the constraint/column that rejected it).
 *
 * <p>Surfaced so a compile reports "29/30 pages compiled, 'osmosis' failed
 * (conflict_note)" instead of silently dropping content or failing the whole batch.
 */
public record FailedPage(String slug, String reason) {}
