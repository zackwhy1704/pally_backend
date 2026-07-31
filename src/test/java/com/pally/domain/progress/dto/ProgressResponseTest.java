package com.pally.domain.progress.dto;

import com.pally.domain.progress.ProgressSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Proves ProgressResponse.from resolves nextUnlockLabel per the summary's
/// carried preferredLocale — the third consumer of LevelRewards.label(),
/// distinct from the achievements list and the level-roadmap endpoint.
class ProgressResponseTest {

    private ProgressSummary summaryAt(int level, String locale) {
        return new ProgressSummary(
                "user-1", 0, level, 0, 0, 0, 0, 0, 0, 0,
                List.of(), List.of(), locale);
    }

    @Test
    void enLocale_nextUnlockLabelIsEnglish() {
        var response = ProgressResponse.from(summaryAt(1, "en"));

        assertThat(response.nextUnlockLevel()).isEqualTo(2);
        assertThat(response.nextUnlockLabel()).isEqualTo("New Mochi colour");
    }

    @Test
    void zhLocale_nextUnlockLabelIsChinese() {
        var response = ProgressResponse.from(summaryAt(1, "zh"));

        assertThat(response.nextUnlockLevel()).isEqualTo(2);
        assertThat(response.nextUnlockLabel())
                .isNotEqualTo("New Mochi colour").isNotBlank();
    }

    @Test
    void pastMaxReward_nextUnlockLabelIsNull() {
        var response = ProgressResponse.from(summaryAt(30, "zh"));

        assertThat(response.nextUnlockLevel()).isNull();
        assertThat(response.nextUnlockLabel()).isNull();
    }
}
