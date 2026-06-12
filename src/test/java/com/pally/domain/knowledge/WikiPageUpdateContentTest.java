package com.pally.domain.knowledge;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that a human-verified wiki page is never downgraded by AI recompile.
 * T3 fix: WikiPage.updateContent() must NOT overwrite certainty or content when
 * humanVerified == true.
 */
class WikiPageUpdateContentTest {

    @Test
    void updateContent_humanVerifiedPage_doesNotDowngradeCertaintyOrContent() {
        // Arrange: a page that was human-verified
        WikiPage page = WikiPage.reconstitute(
                "page-1", "avatar-1", "photosynthesis",
                "Photosynthesis", "Original human-verified content",
                WikiPage.Certainty.VERIFIED, Instant.now(),
                80, "human correction text", Instant.now(), true
        );

        assertThat(page.isHumanVerified()).isTrue();
        assertThat(page.getCertainty()).isEqualTo(WikiPage.Certainty.VERIFIED);
        String originalContent = page.getContent();
        String originalCorrection = page.getHumanCorrection();

        // Act: AI recompile calls updateContent with lower certainty and new content
        page.updateContent("Photosynthesis", "AI-synthesised replacement content",
                WikiPage.Certainty.INFERRED);

        // Assert: VERIFIED certainty is preserved, content NOT overwritten
        assertThat(page.getCertainty())
                .as("VERIFIED page must not be downgraded to INFERRED")
                .isEqualTo(WikiPage.Certainty.VERIFIED);
        assertThat(page.getContent())
                .as("Content must not be overwritten when humanVerified=true")
                .isEqualTo(originalContent);
        assertThat(page.getHumanCorrection())
                .as("Human correction must be preserved")
                .isEqualTo(originalCorrection);
    }

    @Test
    void updateContent_unverifiedPage_updatesContentAndCertainty() {
        // Arrange: a normal AI-generated page
        WikiPage page = WikiPage.reconstitute(
                "page-2", "avatar-1", "mitosis",
                "Mitosis", "Old AI content",
                WikiPage.Certainty.INFERRED, Instant.now()
        );

        // Act
        page.updateContent("Mitosis updated", "New AI content", WikiPage.Certainty.INFERRED);

        // Assert: content and certainty are updated normally
        assertThat(page.getTitle()).isEqualTo("Mitosis updated");
        assertThat(page.getContent()).isEqualTo("New AI content");
        assertThat(page.getCertainty()).isEqualTo(WikiPage.Certainty.INFERRED);
    }

    @Test
    void updateContent_verifiedPage_titleIsStillUpdated() {
        // Title update is allowed even for verified pages
        WikiPage page = WikiPage.reconstitute(
                "page-3", "avatar-1", "gravity",
                "Old Title", "Verified content",
                WikiPage.Certainty.VERIFIED, Instant.now(),
                90, "correction", Instant.now(), true
        );

        page.updateContent("New Title", "AI content", WikiPage.Certainty.INFERRED);

        assertThat(page.getTitle())
                .as("Title may be updated even for verified pages")
                .isEqualTo("New Title");
        assertThat(page.getCertainty())
                .as("Certainty must remain VERIFIED")
                .isEqualTo(WikiPage.Certainty.VERIFIED);
        assertThat(page.getContent())
                .as("Content must not be overwritten")
                .isEqualTo("Verified content");
    }

    @Test
    void updateContent_verifiedPage_updatedAtIsRefreshed() throws InterruptedException {
        // updatedAt should still tick forward even for verified pages
        WikiPage page = WikiPage.reconstitute(
                "page-4", "avatar-1", "algebra",
                "Algebra", "Verified content",
                WikiPage.Certainty.VERIFIED, Instant.now(),
                90, "correction", Instant.now(), true
        );
        Instant before = page.getUpdatedAt();
        Thread.sleep(5);

        page.updateContent("Algebra", "New AI content", WikiPage.Certainty.INFERRED);

        assertThat(page.getUpdatedAt())
                .as("updatedAt should be refreshed even for verified pages")
                .isAfter(before);
    }

    @Test
    void setQualityScore_inRangeValue_roundTrips() {
        // BUG 1: setQualityScore takes a 0–100 int (the caller scales the
        // verifier's 0.0–1.0 score before passing it in). A mid-range value
        // must round-trip unchanged — no double-scaling, no clamping.
        WikiPage page = WikiPage.create("avatar-1", "round-trip", "Round Trip", "body");
        page.setQualityScore(70);
        assertThat(page.getQualityScore())
                .as("a 0–100 score must round-trip unchanged")
                .isEqualTo(70);
    }

    @Test
    void setQualityScore_outOfRangeValues_clampedTo0And100() {
        WikiPage page = WikiPage.create("avatar-1", "clamp", "Clamp", "body");
        page.setQualityScore(-5);
        assertThat(page.getQualityScore()).as("below 0 clamps to 0").isEqualTo(0);
        page.setQualityScore(150);
        assertThat(page.getQualityScore()).as("above 100 clamps to 100").isEqualTo(100);
    }
}
