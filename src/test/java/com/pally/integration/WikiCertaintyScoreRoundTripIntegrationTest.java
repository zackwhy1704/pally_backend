package com.pally.integration;

import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG 2 — locks the {@code certaintyScore} DB round-trip against a real
 * Postgres (Testcontainers).
 *
 * <p>{@link com.pally.infrastructure.persistence.knowledge.WikiPageJpaEntity#toDomain()}
 * is the ONLY production path that rehydrates a {@link WikiPage} from the DB,
 * and it uses the FULL {@code reconstitute(...)} overload that carries
 * {@code certaintyScore}. Three shorter overloads default it to 0.5 and are
 * used only by unit-test fixtures. This test proves that a page saved with
 * {@code certaintyScore = 0.8} reloads as 0.8 — not the 0.5 default — via
 * every adapter load path (findById / findByAvatarIdAndSlug /
 * findActiveByAvatarId / findByAvatarId).
 */
class WikiCertaintyScoreRoundTripIntegrationTest extends IntegrationTestBase {

    @Autowired
    WikiRepository wikiRepository;

    private String avatarId;

    @BeforeEach
    void setUp() {
        AuthResult owner = registerConsentedUser(
                "certainty-owner-" + System.nanoTime() + "@test.com", "password123");
        Map<String, Object> avatarBody = Map.of(
                "name", "MathBot", "subject", "MATHS", "characterType", "MOCHI");
        ResponseEntity<Map> createResp = post("/api/v1/avatars", owner.token(), avatarBody);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        avatarId = (String) ((Map<String, Object>) createResp.getBody().get("data")).get("id");
    }

    @Test
    void certaintyScore_survivesSaveAndReloadAcrossAllAdapterPaths() {
        // Full reconstitute overload — the same one toDomain() uses — so we can
        // pin a non-default certaintyScore of 0.8 before persisting.
        WikiPage page = WikiPage.reconstitute(
                com.pally.shared.util.IdGenerator.newId(), avatarId,
                "decimals", "Decimals", "A decimal is a number with a fractional part.",
                WikiPage.Certainty.INFERRED, Instant.now(),
                70, null, null, false,
                null, 0, 0.8,
                WikiPage.Status.ACTIVE, false, null);

        String savedId = wikiRepository.save(page).getId();

        // save() itself returns toDomain() — must not reset to 0.5.
        assertThat(wikiRepository.save(page).getCertaintyScore())
                .as("save() round-trip preserves certaintyScore")
                .isEqualTo(0.8);

        assertThat(wikiRepository.findById(savedId).orElseThrow().getCertaintyScore())
                .as("findById reload preserves certaintyScore (not the 0.5 default)")
                .isEqualTo(0.8);

        assertThat(wikiRepository.findByAvatarIdAndSlug(avatarId, "decimals")
                .orElseThrow().getCertaintyScore())
                .as("findByAvatarIdAndSlug reload preserves certaintyScore")
                .isEqualTo(0.8);

        List<WikiPage> active = wikiRepository.findActiveByAvatarId(avatarId);
        assertThat(active).anySatisfy(p ->
                assertThat(p.getCertaintyScore())
                        .as("findActiveByAvatarId reload preserves certaintyScore")
                        .isEqualTo(0.8));

        assertThat(wikiRepository.findByAvatarId(avatarId))
                .anySatisfy(p -> assertThat(p.getCertaintyScore())
                        .as("findByAvatarId reload preserves certaintyScore")
                        .isEqualTo(0.8));
    }

    @Test
    void conflictNote_survivesSaveAndReload() {
        // Locks the V73 conflict_note column end-to-end against real Postgres.
        WikiPage page = WikiPage.create(avatarId, "ratios", "Ratios",
                "A ratio compares two quantities.");
        page.setConflictNote("disagrees with the fractions page");
        String savedId = wikiRepository.save(page).getId();

        assertThat(wikiRepository.findById(savedId).orElseThrow().getConflictNote())
                .as("conflict_note column round-trips through the DB")
                .isEqualTo("disagrees with the fractions page");
    }
}
