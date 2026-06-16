package com.pally.domain.chat.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.chat.usecase.SendMessageUseCase;
import com.pally.domain.knowledge.WikiPage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the closed-book keyword-relevance helper in SendMessageUseCase.
 * These tests verify the deterministic scoring logic without wiring up
 * the full Spring context or any mocks beyond a minimal Avatar.
 */
class SendMessageUseCaseClosedBookTest {

    // A minimal stub that exposes the package-visible computeKeywordRelevance method.
    // We use reflection via Spring's ReflectionTestUtils to call the package-private method.

    private final SendMessageUseCase useCase = createMinimalUseCase();

    // ── computeKeywordRelevance ────────────────────────────────────────────

    @Test
    void relevance_emptyPages_returnsZero() {
        double score = useCase.computeKeywordRelevance("photosynthesis", List.of());
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void relevance_nullQuery_returnsZero() {
        WikiPage page = WikiPage.create("av1", "slug1", "Photosynthesis", "Plants make food from sunlight");
        double score = useCase.computeKeywordRelevance(null, List.of(page));
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void relevance_blankQuery_returnsZero() {
        WikiPage page = WikiPage.create("av1", "slug1", "Photosynthesis", "Plants make food from sunlight");
        double score = useCase.computeKeywordRelevance("   ", List.of(page));
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void relevance_perfectMatch_returnsHighScore() {
        WikiPage page = WikiPage.create("av1", "slug1", "Photosynthesis",
                "photosynthesis sunlight glucose chlorophyll");
        // Query tokens (len>2): "photosynthesis", "sunlight", "glucose" = 3 of 3 → score=1.0
        double score = useCase.computeKeywordRelevance(
                "photosynthesis sunlight glucose", List.of(page));
        assertThat(score).isGreaterThan(0.55);
    }

    @Test
    void relevance_completelyUnrelated_returnsLowScore() {
        WikiPage page = WikiPage.create("av1", "slug1", "Photosynthesis",
                "Photosynthesis converts sunlight into glucose using chlorophyll");
        // Query has no token overlap with the page
        double score = useCase.computeKeywordRelevance(
                "what is the capital city of France", List.of(page));
        assertThat(score).isLessThan(0.55);
    }

    @Test
    void relevance_partialMatch_returnsMidRangeScore() {
        WikiPage page = WikiPage.create("av1", "slug1", "Algebra",
                "Quadratic equations are solved using the quadratic formula");
        // Some tokens match (quadratic, equations) but others don't (weather, today)
        double score = useCase.computeKeywordRelevance(
                "solve quadratic equations today", List.of(page));
        // "quadratic" and "equations" are both in page → at least 2/4 = 0.5 overlap
        assertThat(score).isGreaterThan(0.0);
    }

    @Test
    void relevance_multiplePages_takesMaxScore() {
        // Page 1: unrelated
        WikiPage page1 = WikiPage.create("av1", "slug1", "History",
                "The French Revolution began in 1789");
        // Page 2: directly relevant
        WikiPage page2 = WikiPage.create("av1", "slug2", "Biology",
                "The mitochondria is the powerhouse of the cell");
        double score = useCase.computeKeywordRelevance(
                "explain the mitochondria powerhouse", List.of(page1, page2));
        // Should pick page2's score (high overlap)
        assertThat(score).isGreaterThan(0.55);
    }

    @Test
    void relevance_shortWords_filteredOut() {
        // Tokens shorter than 3 chars ("is", "a", "of") should be stripped before scoring
        WikiPage page = WikiPage.create("av1", "slug1", "Grammar",
                "is a of the");
        // All query tokens are ≤2 chars → no tokens survive → score = 0
        double score = useCase.computeKeywordRelevance("is a of", List.of(page));
        assertThat(score).isEqualTo(0.0);
    }

    // ── Factory for a minimal SendMessageUseCase ──────────────────────────

    /**
     * Creates a SendMessageUseCase with all dependencies null — only safe for
     * calling computeKeywordRelevance, which has no external dependencies.
     * All @Value fields are set via ReflectionTestUtils.
     */
    private SendMessageUseCase createMinimalUseCase() {
        SendMessageUseCase uc = new SendMessageUseCase(
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null
        );
        ReflectionTestUtils.setField(uc, "closedBookEnabled", true);
        ReflectionTestUtils.setField(uc, "closedBookThreshold", 0.55);
        ReflectionTestUtils.setField(uc, "closedBookRefusal",
                "That's outside what {brand} covers in your materials.");
        return uc;
    }
}
