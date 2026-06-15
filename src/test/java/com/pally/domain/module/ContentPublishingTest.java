package com.pally.domain.module;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the content publishing lifecycle:
 * DRAFT → APPROVED → LIVE, or DRAFT → REJECTED (delete), or LIVE → ARCHIVED.
 */
class ContentPublishingTest {

    @Test
    void newItem_defaultStatus_isLive() {
        ModuleContentItem item = new ModuleContentItem();
        assertThat(item.getStatus()).isEqualTo("LIVE");
    }

    @Test
    void centreItem_draftToApprovedToLive() {
        ModuleContentItem item = new ModuleContentItem();
        item.setStatus("DRAFT");

        assertThat(item.getStatus()).isEqualTo("DRAFT");

        item.setStatus("APPROVED");
        assertThat(item.getStatus()).isEqualTo("APPROVED");

        item.setStatus("LIVE");
        assertThat(item.getStatus()).isEqualTo("LIVE");
    }

    @Test
    void liveItem_canBeArchived() {
        ModuleContentItem item = new ModuleContentItem();
        item.setStatus("LIVE");

        item.setStatus("ARCHIVED");
        assertThat(item.getStatus()).isEqualTo("ARCHIVED");
    }

    @Test
    void draftItem_canBeRejected() {
        ModuleContentItem item = new ModuleContentItem();
        item.setStatus("DRAFT");

        // Rejection = delete; we verify the status transitions are valid
        item.setStatus("REJECTED");
        assertThat(item.getStatus()).isEqualTo("REJECTED");
    }

    @Test
    void personalAvatarItem_autoLive() {
        // Personal avatar items should be created with LIVE status
        ModuleContentItem item = new ModuleContentItem();
        // Default is LIVE — no manual override needed for personal avatars
        assertThat(item.getStatus()).isEqualTo("LIVE");
    }
}
