package com.pally.infrastructure.persistence.knowledge;

import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiPageIndex;
import com.pally.domain.knowledge.WikiRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class WikiRepositoryAdapter implements WikiRepository {

    private final WikiPageJpaRepository wikiJpaRepository;

    @Override
    @Transactional
    public WikiPage save(WikiPage wikiPage) {
        return wikiJpaRepository.save(WikiPageJpaEntity.fromDomain(wikiPage)).toDomain();
    }

    @Override
    @Transactional
    public List<WikiPage> saveAll(List<WikiPage> pages) {
        return wikiJpaRepository.saveAll(pages.stream().map(WikiPageJpaEntity::fromDomain).toList())
                .stream().map(WikiPageJpaEntity::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WikiPage> findByAvatarIdAndSlug(String avatarId, String slug) {
        return wikiJpaRepository.findByAvatarIdAndSlug(avatarId, slug).map(WikiPageJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WikiPage> findByAvatarId(String avatarId) {
        return wikiJpaRepository.findByAvatarId(avatarId).stream()
                .map(WikiPageJpaEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public int countByAvatarId(String avatarId) {
        return wikiJpaRepository.countByAvatarId(avatarId);
    }

    @Override
    @Transactional
    public void deleteByAvatarId(String avatarId) {
        wikiJpaRepository.deleteByAvatarId(avatarId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WikiPageIndex> getIndex(String avatarId) {
        return wikiJpaRepository.findActiveByAvatarId(avatarId).stream()
                .map(e -> new WikiPageIndex(
                        e.getSlug(),
                        e.getTitle(),
                        e.getCertainty(),
                        e.getCertaintyScore(),
                        extractSummary(e.getContent())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<WikiPage> findByKeywords(String avatarId, List<String> keywords, int maxPages) {
        if (keywords == null || keywords.isEmpty()) return List.of();
        List<WikiPage> active = wikiJpaRepository
                .findActiveByAvatarId(avatarId).stream()
                .map(WikiPageJpaEntity::toDomain)
                .toList();

        // R4 — content-aware retrieval, two passes.
        // Pass 1: TopicRouter already returns slugs from the index; trust an
        // exact slug match first to preserve its ordering.
        // Pass 2: fall back to substring across slug, title AND content so
        // routed topics don't get dropped just because their title wording
        // doesn't include the literal keyword.
        java.util.LinkedHashMap<String, WikiPage> picked =
                new java.util.LinkedHashMap<>();

        for (String kw : keywords) {
            String k = kw.toLowerCase(Locale.ROOT).trim();
            active.stream()
                    .filter(p -> p.getSlug().toLowerCase(Locale.ROOT).equals(k))
                    .findFirst()
                    .ifPresent(p -> picked.putIfAbsent(p.getSlug(), p));
        }
        for (String kw : keywords) {
            String k = kw.toLowerCase(Locale.ROOT).trim();
            for (WikiPage p : active) {
                if (picked.containsKey(p.getSlug())) continue;
                String hay =
                        (p.getSlug() + " " + p.getTitle() + " " + p.getContent())
                                .toLowerCase(Locale.ROOT);
                if (hay.contains(k)) picked.put(p.getSlug(), p);
            }
        }
        return picked.values().stream().limit(maxPages).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<WikiPage> findPrerequisitesOf(String avatarId, String slug) {
        return wikiJpaRepository.findByAvatarIdAndSlug(avatarId, slug)
                .map(e -> e.getPrerequisiteSlugs())
                .filter(prereqs -> prereqs != null && !prereqs.isBlank())
                .map(prereqs -> Arrays.stream(prereqs.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .flatMap(prereqSlug -> wikiJpaRepository
                                .findByAvatarIdAndSlug(avatarId, prereqSlug)
                                .map(WikiPageJpaEntity::toDomain)
                                .stream())
                        .toList())
                .orElse(List.of());
    }

    @Override
    @Transactional
    public void recordRetrieval(String avatarId, List<String> slugs) {
        if (!slugs.isEmpty()) {
            wikiJpaRepository.recordRetrieval(avatarId, slugs, Instant.now());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public long estimateTokenCount(String avatarId) {
        Long charCount = wikiJpaRepository.sumContentLengthByAvatarId(avatarId);
        return charCount != null ? charCount / 4 : 0L;
    }

    @Override
    @Transactional
    public void adjustCertainty(String avatarId, List<String> slugs, double delta) {
        if (slugs == null || slugs.isEmpty()) return;
        wikiJpaRepository.adjustCertainty(avatarId, slugs, delta, Instant.now());
    }

    @Override
    @Transactional
    public void recordQuizUsage(String avatarId, List<String> slugs) {
        if (slugs == null || slugs.isEmpty()) return;
        wikiJpaRepository.incrementQuizUseCount(avatarId, slugs);
    }

    @Override
    @Transactional
    public void setReviewRequired(String avatarId, List<String> slugs, boolean value) {
        if (slugs == null || slugs.isEmpty()) return;
        wikiJpaRepository.setReviewRequired(avatarId, slugs, value);
    }

    @Override
    @Transactional
    public int archiveStalePages(String avatarId, Instant cutoff) {
        return wikiJpaRepository.archiveStalePages(avatarId, cutoff);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WikiPage> findReviewRequired(String avatarId) {
        return wikiJpaRepository.findReviewRequired(avatarId).stream()
                .map(WikiPageJpaEntity::toDomain)
                .toList();
    }

    private String extractSummary(String content) {
        if (content == null || content.isBlank()) return "";
        int nl = content.indexOf('\n');
        String first = nl > 0 ? content.substring(0, nl) : content;
        return first.length() > 120 ? first.substring(0, 120) : first;
    }
}
