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
    int countActiveByAvatarId(String avatarId);

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

    // A3: archive ACTIVE pages whose slug is NOT in the surviving list.
    // If survivingSlugs is empty, archives ALL active pages for the avatar.
    // Returns the count of pages archived.
    int archiveOrphanPages(String avatarId, List<String> survivingSlugs);

    // A3: find avatarIds where files are newer than wiki pages OR files exist but 0 active pages.
    List<String> findAvatarIdsNeedingRecompile();

    // Fix 1: Only ACTIVE pages — used by context assembler so deleted-file pages never reach chat.
    List<WikiPage> findActiveByAvatarId(String avatarId);

    // Fix 4: Slugs of pages archived within the last `since` window — used to build the
    // "deleted topics" hint in the dynamic tail of the system prompt.
    List<String> findRecentlyArchivedSlugs(String avatarId, java.time.Instant since);
}
