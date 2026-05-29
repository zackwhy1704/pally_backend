package com.pally.domain.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Port for wiki page persistence.
 */
public interface WikiRepository {

    WikiPage save(WikiPage wikiPage);

    List<WikiPage> saveAll(List<WikiPage> pages);

    Optional<WikiPage> findByAvatarIdAndSlug(String avatarId, String slug);

    List<WikiPage> findByAvatarId(String avatarId);

    int countByAvatarId(String avatarId);

    void deleteByAvatarId(String avatarId);

    // Harness: lightweight index for Tier-2 context
    List<WikiPageIndex> getIndex(String avatarId);

    // Harness: keyword-based page search for Tier-3 context
    List<WikiPage> findByKeywords(String avatarId, List<String> keywords, int maxPages);

    // Harness: prerequisite pages for Tier-4 context
    List<WikiPage> findPrerequisitesOf(String avatarId, String slug);

    // Harness: record that these pages were loaded into context
    void recordRetrieval(String avatarId, List<String> slugs);

    // Brain budget: approximate token count (1 token ≈ 4 chars)
    long estimateTokenCount(String avatarId);

    // Harness feedback: nudge certainty for specific slugs (+ reinforce, − shake)
    void adjustCertainty(String avatarId, List<String> slugs, double delta);

    // Harness feedback: increment quiz usage counters
    void recordQuizUsage(String avatarId, List<String> slugs);

    // Harness feedback: flag/unflag review-required
    void setReviewRequired(String avatarId, List<String> slugs, boolean value);

    // R6: mark pages not retrieved since `cutoff` as ARCHIVED (returns count)
    int archiveStalePages(String avatarId, Instant cutoff);

    // R8: pages currently flagged for review
    List<WikiPage> findReviewRequired(String avatarId);
}
