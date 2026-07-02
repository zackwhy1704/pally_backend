package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.chat.ChatSessionSummariser;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiPageIndex;
import com.pally.domain.knowledge.WikiRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Fix 4 — recently-archived slug hint appears in Block 4 (dynamic tail) only
 * when there are archived slugs, and is absent when the archive list is empty.
 *
 * <p>Zero real Claude API calls — all dependencies are mocked.
 */
@ExtendWith(MockitoExtension.class)
class ArchivedSlugHintTest {

    @Mock WikiRepository wikiRepository;
    @Mock com.pally.domain.chat.ChatRepository chatRepository;
    @Mock TopicRouter topicRouter;
    @Mock ChatSessionSummariser sessionSummariser;

    private ClaudeContextAssembler assembler;

    private static final String AVATAR_ID = "avatar-archived-hint";

    private Avatar avatar;
    private WikiPage activePage;

    @BeforeEach
    void setUp() {
        assembler = new ClaudeContextAssembler(
                topicRouter, wikiRepository, org.mockito.Mockito.mock(com.pally.domain.weakness.WeaknessProfileService.class), chatRepository, new ObjectMapper(), sessionSummariser,
                new CalculatorTool(), new AlgebraTool());

        avatar = Avatar.reconstitute(
                AVATAR_ID, "user-1", "Mochi", Subject.MATHS, CharacterType.MOCHI,
                1, Instant.now());

        activePage = WikiPage.create(AVATAR_ID, "fractions", "Fractions",
                "A fraction is part of a whole.");

        // Use lenient() for all @BeforeEach stubs to avoid UnnecessaryStubbingException.
        lenient().when(wikiRepository.getIndex(AVATAR_ID)).thenReturn(List.of(
                new WikiPageIndex("fractions", "Fractions", WikiPage.Certainty.INFERRED, 0.5, "A fraction")));

        lenient().when(topicRouter.route(any(), any(), anyList())).thenReturn(List.of());

        lenient().when(wikiRepository.findByKeywords(any(), anyList(), anyInt())).thenReturn(List.of());
        lenient().when(wikiRepository.findPrerequisitesOf(any(), any())).thenReturn(List.of());

        lenient().when(wikiRepository.findActiveByAvatarId(AVATAR_ID)).thenReturn(List.of(activePage));

        lenient().when(sessionSummariser.findSummary(any())).thenReturn(Optional.empty());
    }

    @Test
    void block4_withRecentlyArchivedSlug_containsDeletedTopicsHint() {
        // Seed an archived slug updated 2 days ago
        when(wikiRepository.findRecentlyArchivedSlugs(any(), any()))
                .thenReturn(List.of("photosynthesis"));

        List<Map<String, Object>> blocks = assembler.assemble(avatar, "Tell me about photosynthesis")
                .systemBlocks();

        // Block 4 is the last block (dynamic tail, no cache_control)
        String block4Text = findBlock4Text(blocks);

        assertThat(block4Text).contains("photosynthesis");
        assertThat(block4Text).contains("DELETED TOPICS");
        assertThat(block4Text).contains("no longer in your uploaded notes");
    }

    @Test
    void block4_noArchivedSlugs_doesNotContainDeletedTopicsHint() {
        // No recently-archived slugs
        when(wikiRepository.findRecentlyArchivedSlugs(any(), any()))
                .thenReturn(List.of());

        List<Map<String, Object>> blocks = assembler.assemble(avatar, "What are fractions?")
                .systemBlocks();

        String block4Text = findBlock4Text(blocks);

        assertThat(block4Text).doesNotContain("DELETED TOPICS");
        assertThat(block4Text).doesNotContain("no longer in your uploaded notes");
    }

    @Test
    void block4_multipleArchivedSlugs_allAppearInHint() {
        when(wikiRepository.findRecentlyArchivedSlugs(any(), any()))
                .thenReturn(List.of("photosynthesis", "osmosis", "cell-division"));

        List<Map<String, Object>> blocks = assembler.assemble(avatar, "What did we delete?")
                .systemBlocks();

        String block4Text = findBlock4Text(blocks);

        assertThat(block4Text).contains("photosynthesis");
        assertThat(block4Text).contains("osmosis");
        assertThat(block4Text).contains("cell-division");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Returns the text of Block 4 — the last block that has no cache_control key.
     * This is the dynamic tail block added by the assembler.
     */
    private String findBlock4Text(List<Map<String, Object>> blocks) {
        // Block 4 has no cache_control — find the last block without it
        for (int i = blocks.size() - 1; i >= 0; i--) {
            if (!blocks.get(i).containsKey("cache_control")) {
                Object text = blocks.get(i).get("text");
                return text != null ? text.toString() : "";
            }
        }
        return "";
    }
}
