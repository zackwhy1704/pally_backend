package com.pally.domain.knowledge;

import com.pally.infrastructure.persistence.knowledge.KnowledgeFileJpaEntity;
import com.pally.infrastructure.persistence.knowledge.KnowledgeFileJpaRepository;
import com.pally.shared.exception.DuplicateContentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContentDeduplicatorTest {

    @Mock KnowledgeFileJpaRepository fileRepo;
    @InjectMocks ContentDeduplicator deduplicator;

    @BeforeEach
    void noExactByDefault() {
        // Default: no exact duplicate. lenient so pure hash/Jaccard tests don't
        // need to stub the repo at all.
        org.mockito.Mockito.lenient()
                .when(fileRepo.existsByAvatarIdAndContentHashAndStatusNot(any(), any(), any()))
                .thenReturn(false);
    }

    // ── Hash ─────────────────────────────────────────────────────────────────

    @Test
    void computeHash_sameText_sameHash() {
        String h1 = deduplicator.computeHash("Hello world");
        String h2 = deduplicator.computeHash("Hello world");
        assertThat(h1).isEqualTo(h2).hasSize(64);
    }

    @Test
    void computeHash_differentText_differentHash() {
        assertThat(deduplicator.computeHash("Hello"))
                .isNotEqualTo(deduplicator.computeHash("World"));
    }

    @Test
    void computeHash_normalisesCase() {
        assertThat(deduplicator.computeHash("Hello World"))
                .isEqualTo(deduplicator.computeHash("HELLO WORLD"));
    }

    // ── Exact duplicate ───────────────────────────────────────────────────────

    @Test
    void check_exactDuplicate_throwsDuplicateContentException() {
        when(fileRepo.existsByAvatarIdAndContentHashAndStatusNot(eq("av1"), any(), any()))
                .thenReturn(true);
        KnowledgeFileJpaEntity existing = makeEntity("photosynthesis.pdf", "hash1");
        when(fileRepo.findByAvatarId("av1")).thenReturn(List.of(existing));

        assertThatThrownBy(() -> deduplicator.check("av1", "same text", "new.pdf"))
                .isInstanceOf(DuplicateContentException.class)
                .satisfies(e -> {
                    var ex = (DuplicateContentException) e;
                    assertThat(ex.getKind()).isEqualTo(DuplicateContentException.Kind.EXACT);
                    assertThat(ex.getSimilarity()).isEqualTo(1.0);
                });
    }

    // ── Jaccard similarity ────────────────────────────────────────────────────

    @Test
    void jaccard_identical_isOne() {
        var tokens = ContentDeduplicator.tokenise("the cat sat on the mat");
        assertThat(ContentDeduplicator.jaccard(tokens, tokens)).isEqualTo(1.0);
    }

    @Test
    void jaccard_disjoint_isZero() {
        var a = ContentDeduplicator.tokenise("apple banana cherry");
        var b = ContentDeduplicator.tokenise("dog elephant fox");
        assertThat(ContentDeduplicator.jaccard(a, b)).isEqualTo(0.0);
    }

    @Test
    void check_highSimilarity_throwsSimilarContent() {
        // Build a long existing text that's mostly the same as new text
        String base = "photosynthesis is the process by which plants use sunlight "
                + "water and carbon dioxide to produce oxygen and energy in the form of sugar "
                + "chlorophyll absorbs light energy the light reactions occur in the thylakoids "
                + "the calvin cycle occurs in the stroma of the chloroplast";
        String newText  = base + " additional minor note about chlorophyll";
        String existing = base + " slightly different ending about stroma";

        KnowledgeFileJpaEntity kf = makeEntityWithText("biology.pdf", existing);
        when(fileRepo.findByAvatarId("av1")).thenReturn(List.of(kf));

        assertThatThrownBy(() -> deduplicator.check("av1", newText, "new.pdf"))
                .isInstanceOf(DuplicateContentException.class)
                .satisfies(e -> {
                    var ex = (DuplicateContentException) e;
                    assertThat(ex.getKind()).isEqualTo(DuplicateContentException.Kind.SIMILAR);
                    assertThat(ex.getSimilarity()).isGreaterThanOrEqualTo(
                            ContentDeduplicator.SIMILAR_THRESHOLD);
                });
    }

    @Test
    void check_lowSimilarity_passes() {
        // Both texts > MIN_TOKENS (30) but on completely different topics.
        // Token counts after normalisation must each be >= 30.
        String existing = "photosynthesis is the process by which green plants use sunlight "
                + "water and carbon dioxide to produce glucose and oxygen chlorophyll "
                + "absorbs light energy and converts it into chemical energy stored "
                + "in atp molecules the light dependent reactions occur in the thylakoid "
                + "membranes and the calvin cycle occurs in the stroma";
        String newText = "quadratic equations can be solved by factoring completing the "
                + "square or using the quadratic formula the discriminant determines "
                + "the number of real solutions parabolas open upward when the leading "
                + "coefficient is positive and downward when it is negative vertex form "
                + "reveals the maximum or minimum point of the parabola systems of "
                + "equations can be solved by substitution elimination or graphing";

        KnowledgeFileJpaEntity kf = makeEntityWithText("bio.pdf", existing);
        when(fileRepo.findByAvatarId("av1")).thenReturn(List.of(kf));

        assertThatNoException().isThrownBy(
                () -> deduplicator.check("av1", newText, "new.pdf"));
    }

    @Test
    void check_shortText_skippedForSimilarity() {
        // Under MIN_TOKENS — findByAvatarId is never called; no stub needed.
        String tooShort = "photosynthesis plants";
        assertThatNoException().isThrownBy(
                () -> deduplicator.check("av1", tooShort, "new.pdf"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private KnowledgeFileJpaEntity makeEntity(String name, String hash) {
        return makeEntityWithText(name, "some content " + hash);
    }

    private KnowledgeFileJpaEntity makeEntityWithText(String name, String text) {
        KnowledgeFile kf = KnowledgeFile.reconstitute(
                name, "av1", "user1", name, "key", 1,
                KnowledgeFile.UploadType.PDF, KnowledgeFile.Status.READY,
                java.time.Instant.now(), text);
        kf.setContentHash(ContentDeduplicator.sha256(ContentDeduplicator.normalise(text)));
        return com.pally.infrastructure.persistence.knowledge
                .KnowledgeFileJpaEntity.fromDomain(kf);
    }
}
