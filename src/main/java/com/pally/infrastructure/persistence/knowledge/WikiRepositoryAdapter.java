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
        if (keywords.isEmpty()) return List.of();
        List<WikiPage> all = wikiJpaRepository.findActiveByAvatarId(avatarId).stream()
                .map(WikiPageJpaEntity::toDomain)
                .toList();
        return all.stream()
                .filter(p -> matchesAnyKeyword(p, keywords))
                .limit(maxPages)
                .toList();
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

    private boolean matchesAnyKeyword(WikiPage page, List<String> keywords) {
        String slugLower = page.getSlug().toLowerCase(Locale.ROOT);
        String titleLower = page.getTitle().toLowerCase(Locale.ROOT);
        return keywords.stream().anyMatch(kw -> {
            String kwLower = kw.toLowerCase(Locale.ROOT);
            return slugLower.contains(kwLower) || titleLower.contains(kwLower);
        });
    }

    private String extractSummary(String content) {
        if (content == null || content.isBlank()) return "";
        int nl = content.indexOf('\n');
        String first = nl > 0 ? content.substring(0, nl) : content;
        return first.length() > 120 ? first.substring(0, 120) : first;
    }
}
