package com.pally.domain.progress;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Locks the en/zh resolution added for the achievements localization pass.
/// This is a STATIC DATA catalog (two independent authored strings), not an
/// LLM prompt directive, so the byte-identical-en proof here is a SNAPSHOT
/// comparison against the exact pre-change hardcoded values — not a
/// `zh == en + directive` equality (there is no directive being appended).
class AchievementCatalogTest {

    /// The exact (name, description) pairs as they existed before this PR —
    /// copied verbatim from the pre-change hardcoded literals. If en drifts
    /// from this snapshot, this test fails: proof the localization pass did
    /// not alter the English source of truth.
    private static final Map<String, String[]> EN_SNAPSHOT = Map.ofEntries(
            Map.entry("STREAK_3", new String[]{"On a Roll", "3-day streak"}),
            Map.entry("STREAK_7", new String[]{"Week Warrior", "7-day streak"}),
            Map.entry("STREAK_30", new String[]{"Month of Mastery", "30-day streak"}),
            Map.entry("FIRST_CHAT", new String[]{"First Question", "Ask your tutor anything"}),
            Map.entry("FIRST_QUIZ", new String[]{"Pop Quiz", "Take your first quiz"}),
            Map.entry("FIRST_UPLOAD", new String[]{"Notebook Open", "Upload your first study notes"}),
            Map.entry("PHOTOS_10", new String[]{"Snap Solver", "Solve 10 photo questions"}),
            Map.entry("QUIZ_CORRECT_50", new String[]{"Quiz Whiz", "Get 50 quiz answers correct"}),
            Map.entry("QUIZ_CORRECT_250", new String[]{"Quiz Champion", "Get 250 quiz answers correct"}),
            Map.entry("PERFECT_QUIZ", new String[]{"Flawless", "Get a perfect quiz score"}),
            Map.entry("LEVEL_5", new String[]{"Rising Star", "Reach Level 5"}),
            Map.entry("LEVEL_10", new String[]{"Shining Star", "Reach Level 10"})
    );

    @Test
    void catalogSize_matchesSnapshot() {
        assertThat(AchievementCatalog.all()).hasSize(EN_SNAPSHOT.size());
    }

    @Test
    void en_isByteIdenticalToPreChangeSnapshot_forEveryDefinition() {
        for (var def : AchievementCatalog.all()) {
            var expected = EN_SNAPSHOT.get(def.id());
            assertThat(expected).as("no snapshot entry for %s", def.id()).isNotNull();
            assertThat(def.name()).as("%s name()", def.id()).isEqualTo(expected[0]);
            assertThat(def.description()).as("%s description()", def.id()).isEqualTo(expected[1]);
            // The locale-aware accessor with "en" (or unknown/null) must return
            // the exact same value as the plain accessor — the resolver adds
            // a zh branch, it never changes what en resolves to.
            assertThat(def.name("en")).isEqualTo(def.name());
            assertThat(def.description("en")).isEqualTo(def.description());
            assertThat(def.name(null)).isEqualTo(def.name());
            assertThat(def.name("fr")).isEqualTo(def.name());
        }
    }

    @Test
    void zh_isNonBlankAndDiffersFromEn_forEveryDefinition() {
        for (var def : AchievementCatalog.all()) {
            assertThat(def.name("zh")).as("%s zh name", def.id())
                    .isNotBlank().isNotEqualTo(def.name());
            assertThat(def.description("zh")).as("%s zh description", def.id())
                    .isNotBlank().isNotEqualTo(def.description());
        }
    }

    @Test
    void category_and_rarity_areCodesNotTranslatedText() {
        // Category/Rarity are rendered as enum .name() codes by the controller
        // (e.g. for client-side icon/filter switches), never natural-language
        // text — so they carry no locale-aware accessor, unlike name/description.
        var def = AchievementCatalog.byId("STREAK_7");
        assertThat(def.category()).isEqualTo(AchievementCatalog.Category.STREAK);
        assertThat(def.rarity()).isEqualTo(AchievementCatalog.Rarity.RARE);
    }
}
