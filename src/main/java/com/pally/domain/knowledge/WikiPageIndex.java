package com.pally.domain.knowledge;

/**
 * Lightweight view of a wiki page used for Tier-2 context assembly.
 * Contains only the fields needed to build the index summary sent in every prompt.
 */
public record WikiPageIndex(
        String slug,
        String title,
        WikiPage.Certainty certainty,
        double certaintyScore,
        String summary
) {}
