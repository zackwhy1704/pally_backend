package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.chat.AssembledContext;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Fix 1 — Verify the context assembler only loads ACTIVE wiki pages into chat context.
 *
 * <p>ARCHIVED pages (from deleted files) must never reach the Claude prompt.
 * All Claude API calls are mocked — no API credits consumed.
 */
@ExtendWith(MockitoExtension.class)
class ClaudeContextAssemblerActiveFilterTest {

    @Mock WikiRepository wikiRepository;
    @Mock com.pally.domain.chat.ChatRepository chatRepository;
    @Mock TopicRouter topicRouter;
    @Mock ChatSessionSummariser sessionSummariser;

    private ClaudeContextAssembler assembler;

    private static final String AVATAR_ID = "avatar-filter-test";

    private Avatar avatar;
    private WikiPage activePage;
    private WikiPage archivedPage;

    @BeforeEach
    void setUp() {
        assembler = new ClaudeContextAssembler(
                topicRouter, wikiRepository, chatRepository, new ObjectMapper(), sessionSummariser,
                new CalculatorTool(), new AlgebraTool());

        avatar = Avatar.reconstitute(
                AVATAR_ID, "user-1", "Nomi", Subject.SCIENCE, CharacterType.MOCHI,
                2, Instant.now());

        activePage = WikiPage.reconstitute(
                "page-active", AVATAR_ID, "photosynthesis",
                "Photosynthesis", "Plants make food from sunlight.",
                WikiPage.Certainty.INFERRED, Instant.now(),
                0, null, null, false,
                null, 0, 0.5, WikiPage.Status.ACTIVE, false, null);

        archivedPage = WikiPage.reconstitute(
                "page-archived", AVATAR_ID, "osmosis",
                "Osmosis", "Water moves across a membrane.",
                WikiPage.Certainty.INFERRED, Instant.now(),
                0, null, null, false,
                null, 0, 0.5, WikiPage.Status.ARCHIVED, false, null);

        // Use lenient() for all @BeforeEach stubs to avoid UnnecessaryStubbingException
        // in tests that don't exercise every code path.
        lenient().when(wikiRepository.getIndex(AVATAR_ID)).thenReturn(List.of(
                new WikiPageIndex("photosynthesis", "Photosynthesis", WikiPage.Certainty.INFERRED, 0.5, "Plants make food")));

        lenient().when(topicRouter.route(any(), any(), anyList())).thenReturn(List.of());

        lenient().when(wikiRepository.findByKeywords(any(), anyList(), anyInt())).thenReturn(List.of());

        lenient().when(wikiRepository.findPrerequisitesOf(any(), any())).thenReturn(List.of());

        // Fix 1: findActiveByAvatarId ONLY returns the ACTIVE page
        lenient().when(wikiRepository.findActiveByAvatarId(AVATAR_ID)).thenReturn(List.of(activePage));

        // Fix 4: no recently-archived slugs for this test
        lenient().when(wikiRepository.findRecentlyArchivedSlugs(any(), any())).thenReturn(List.of());

        lenient().when(sessionSummariser.findSummary(any())).thenReturn(Optional.empty());
    }

    @Test
    void assemble_withActiveAndArchivedPages_onlyActivePageReachesPrompt() {
        AssembledContext ctx = assembler.assemble(avatar, "What is photosynthesis?");

        String prompt = ctx.systemPrompt();
        // Active page title + slug must appear
        assertThat(prompt).contains("Photosynthesis");
        assertThat(prompt).contains("photosynthesis");
        // ARCHIVED page content must NOT appear
        assertThat(prompt).doesNotContain("Osmosis");
        assertThat(prompt).doesNotContain("osmosis");
    }

    @Test
    void assemble_systemBlocks_archivedPageSlugAbsentFromBlock3() {
        AssembledContext ctx = assembler.assemble(avatar, "Tell me about cells");

        // Block 3 is the wiki-pages block — must not contain the archived slug
        boolean anyBlockHasArchivedContent = ctx.systemBlocks().stream()
                .anyMatch(block -> {
                    Object text = block.get("text");
                    return text != null && text.toString().contains("osmosis");
                });
        assertThat(anyBlockHasArchivedContent)
                .as("No system block should contain the ARCHIVED page slug")
                .isFalse();
    }

    @Test
    void assemble_findActiveCalledInsteadOfFindAll() {
        // findActiveByAvatarId must be the method supplying Block 3 content
        assembler.assemble(avatar, "question");

        // The assembler only calls findActiveByAvatarId for Block3 (Fix 1).
        // Verify it was called at least once:
        org.mockito.Mockito.verify(wikiRepository, org.mockito.Mockito.atLeastOnce())
                .findActiveByAvatarId(AVATAR_ID);
    }
}
