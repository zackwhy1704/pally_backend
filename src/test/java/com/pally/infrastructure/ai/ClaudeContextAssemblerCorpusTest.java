package com.pally.infrastructure.ai;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.chat.AssembledContext;
import com.pally.domain.chat.ChatRepository;
import com.pally.domain.chat.ChatSessionSummariser;
import com.pally.domain.knowledge.DetectedTopic;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiPageIndex;
import com.pally.domain.knowledge.WikiRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests that ClaudeContextAssembler reads the corpus avatar's wiki for centre
 * avatars, and the avatar's own wiki for personal avatars.
 */
@ExtendWith(MockitoExtension.class)
class ClaudeContextAssemblerCorpusTest {

    @Mock private TopicRouter topicRouter;
    @Mock private WikiRepository wikiRepository;
    @Mock private ChatRepository chatRepository;
    @Mock private ChatSessionSummariser sessionSummariser;
    @Mock private CalculatorTool calculatorTool;
    @Mock private AlgebraTool algebraTool;

    private ClaudeContextAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new ClaudeContextAssembler(
                topicRouter, wikiRepository, org.mockito.Mockito.mock(com.pally.domain.weakness.WeaknessProfileService.class), chatRepository,
                new ObjectMapper(), sessionSummariser, calculatorTool, algebraTool);
    }

    @Test
    void centreAvatar_readsCorpusAvatarWiki() {
        Avatar centreAvatar = Avatar.reconstitute(
                "student-av", "user-1", "Centre Math", Subject.MATHS, CharacterType.MOCHI,
                5, java.time.Instant.now(), null, null, Avatar.PedagogyMode.SOCRATIC,
                com.pally.domain.avatar.TeachingMode.TEACHING, null,
                Avatar.BrainState.READY, true, null, true, false);
        centreAvatar.setCorpusAvatarId("corpus-av");

        when(wikiRepository.getIndex("corpus-av")).thenReturn(List.of());
        when(topicRouter.route(anyString(), anyString(), anyList())).thenReturn(List.of());
        when(wikiRepository.findByKeywords(eq("corpus-av"), anyList(), anyInt())).thenReturn(List.of());
        when(sessionSummariser.findSummary("student-av")).thenReturn(java.util.Optional.empty());

        AssembledContext ctx = assembler.assemble(centreAvatar, "what are fractions?");

        // Verify the wiki index was loaded from the corpus avatar, not the student avatar
        verify(wikiRepository).getIndex("corpus-av");
        verify(wikiRepository, never()).getIndex("student-av");
    }

    @Test
    void personalAvatar_readsOwnWiki() {
        Avatar personalAvatar = Avatar.create("user-1", "MyMath", Subject.MATHS, CharacterType.MOCHI);

        when(wikiRepository.getIndex(personalAvatar.getId())).thenReturn(List.of());
        when(topicRouter.route(anyString(), anyString(), anyList())).thenReturn(List.of());
        when(wikiRepository.findByKeywords(eq(personalAvatar.getId()), anyList(), anyInt())).thenReturn(List.of());
        when(sessionSummariser.findSummary(personalAvatar.getId())).thenReturn(java.util.Optional.empty());

        AssembledContext ctx = assembler.assemble(personalAvatar, "what are fractions?");

        // Verify the wiki index was loaded from the personal avatar's own ID
        verify(wikiRepository).getIndex(personalAvatar.getId());
    }
}
