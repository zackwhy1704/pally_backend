package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.chat.HintTreeGenerator;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiQualityVerifier;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.module.ModuleContentGenerator;
import com.pally.infrastructure.ai.ClaudeApiClient;
import com.pally.infrastructure.ai.ClaudeFlashcardGenerator;
import com.pally.infrastructure.ai.ModelRouter;
import com.pally.infrastructure.persistence.module.LearningModuleJpaRepository;
import com.pally.infrastructure.persistence.knowledge.WikiPageSourceJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * FIX B — Near-duplicate wiki page deduplication post-pass.
 *
 * <p>Tests areDuplicates() and deduplicatePages() in isolation via package-private access.
 * Zero real Claude API calls.
 */
@ExtendWith(MockitoExtension.class)
class WikiPageDedupTest {

    @Mock WikiRepository wikiRepository;
    @Mock AvatarRepository avatarRepository;
    @Mock HintTreeGenerator hintTreeGenerator;
    @Mock ClaudeFlashcardGenerator flashcardGenerator;
    @Mock ClaudeApiClient claudeApiClient;
    @Mock ModelRouter modelRouter;
    @Mock WikiPageSourceJpaRepository wikiPageSourceRepo;
    @Mock ModuleContentGenerator moduleContentGenerator;
    @Mock LearningModuleJpaRepository learningModuleRepository;

    private WikiPagePersistenceService service;

    private static final String AVATAR_ID = "avatar-dedup-test";

    @BeforeEach
    void setUp() {
        WikiQualityVerifier wikiQualityVerifier = new WikiQualityVerifier();
        org.springframework.beans.factory.ObjectProvider<WikiPagePersistenceService> selfProvider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        service = new WikiPagePersistenceService(
                wikiRepository, avatarRepository, hintTreeGenerator, flashcardGenerator,
                claudeApiClient, modelRouter, wikiPageSourceRepo,
                moduleContentGenerator, learningModuleRepository, wikiQualityVerifier,
                selfProvider);
        org.mockito.Mockito.lenient().when(selfProvider.getObject()).thenReturn(service);
    }

    // ── areDuplicates() unit tests ────────────────────────────────────────────

    @Test
    void areDuplicates_sameSlugs_returnsTrue() {
        WikiPage a = makePage("page-a", "photosynthesis-plants", "Plants convert sunlight into energy using chlorophyll.");
        WikiPage b = makePage("page-b", "photosynthesis-plants", "Photosynthesis converts light energy into chemical energy in plants.");

        assertThat(service.areDuplicates(a, b))
                .as("Pages with identical slug tokens should be detected as duplicates")
                .isTrue();
    }

    @Test
    void areDuplicates_nearIdenticalSlugTokens_returnsTrue() {
        // "cell-division" vs "cell-divison" (common typo) → slug token overlap
        WikiPage a = makePage("p1", "cell-division-mitosis", "Mitosis is the process where one cell divides into two identical daughter cells.");
        WikiPage b = makePage("p2", "cell-mitosis-division", "During mitosis a cell splits into two daughter cells with the same DNA.");

        assertThat(service.areDuplicates(a, b))
                .as("Pages with highly overlapping slug tokens (≥0.8 Jaccard) should be duplicates")
                .isTrue();
    }

    @Test
    void areDuplicates_highContentOverlap_returnsTrue() {
        // Different slugs but nearly identical content
        String content1 = "Water boils at 100 degrees Celsius at sea level. This is called the boiling point of water.";
        String content2 = "Water boils at 100 degrees Celsius at sea level. The boiling point of water is 100 degrees.";
        WikiPage a = makePage("p1", "boiling-point", content1);
        WikiPage b = makePage("p2", "water-boiling-temp", content2);

        assertThat(service.areDuplicates(a, b))
                .as("Pages with ≥0.75 content Jaccard similarity should be detected as duplicates")
                .isTrue();
    }

    @Test
    void areDuplicates_distinctContent_returnsFalse() {
        WikiPage a = makePage("p1", "photosynthesis",
                "Plants use sunlight water and carbon dioxide to produce glucose and oxygen.");
        WikiPage b = makePage("p2", "respiration",
                "Cells break down glucose using oxygen to release energy producing carbon dioxide and water.");

        assertThat(service.areDuplicates(a, b))
                .as("Pages on different topics should NOT be detected as duplicates")
                .isFalse();
    }

    @Test
    void areDuplicates_emptyContent_returnsFalse() {
        WikiPage a = makePage("p1", "topic-a", "");
        WikiPage b = makePage("p2", "topic-b", "Water boils at 100 degrees.");

        assertThat(service.areDuplicates(a, b))
                .as("Empty-content pages should not be flagged as duplicates")
                .isFalse();
    }

    // ── deduplicatePages() integration tests ──────────────────────────────────

    @Test
    void deduplicatePages_duplicatePair_deletesShortPage() {
        WikiPage richer = makePage("id-rich", "photosynthesis-plants",
                "Plants use sunlight water and carbon dioxide to produce glucose and oxygen through photosynthesis in chloroplasts.");
        WikiPage shorter = makePage("id-short", "photosynthesis-plants",
                "Plants use sunlight to make food called glucose.");

        service.deduplicatePages(AVATAR_ID, List.of(richer, shorter));

        // The shorter page should be deleted; the richer one kept
        verify(wikiRepository, times(1)).deleteById("id-short");
        verify(wikiRepository, never()).deleteById("id-rich");
    }

    @Test
    void deduplicatePages_noDuplicates_noDeletes() {
        WikiPage a = makePage("p1", "photosynthesis",
                "Plants use sunlight water and carbon dioxide to produce glucose.");
        WikiPage b = makePage("p2", "respiration",
                "Cells break down glucose using oxygen to release energy.");

        service.deduplicatePages(AVATAR_ID, List.of(a, b));

        verify(wikiRepository, never()).deleteById(anyString());
    }

    @Test
    void deduplicatePages_singlePage_noDeletes() {
        WikiPage a = makePage("p1", "photosynthesis", "Plants make food from sunlight.");

        service.deduplicatePages(AVATAR_ID, List.of(a));

        verify(wikiRepository, never()).deleteById(anyString());
    }

    @Test
    void deduplicatePages_emptyList_noDeletes() {
        service.deduplicatePages(AVATAR_ID, List.of());

        verify(wikiRepository, never()).deleteById(anyString());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private WikiPage makePage(String id, String slug, String content) {
        return WikiPage.reconstitute(
                id, AVATAR_ID, slug,
                slug, // title == slug for simplicity
                content,
                WikiPage.Certainty.INFERRED, Instant.now(),
                0, null, null, false,
                null, 0, 0.5, WikiPage.Status.ACTIVE, false, null);
    }
}
