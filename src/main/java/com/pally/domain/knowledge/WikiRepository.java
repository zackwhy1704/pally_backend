package com.pally.domain.knowledge;

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
}
