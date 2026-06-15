package com.pally.domain.chat;

import com.pally.infrastructure.ai.GeminiCompletionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatSessionSummariserTest {

    @Mock
    private ChatSessionSummaryRepository summaryRepo;

    @Mock
    private GeminiCompletionService geminiCompletion;

    private ChatSessionSummariser summariser;

    @BeforeEach
    void setUp() {
        summariser = new ChatSessionSummariser(summaryRepo, geminiCompletion);
    }

    @Test
    void findSummary_delegatesToPortAndReturnsValue() {
        when(summaryRepo.findSummaryByAvatarId("avatar-1"))
                .thenReturn(Optional.of("prior summary"));

        Optional<String> result = summariser.findSummary("avatar-1");

        assertThat(result).contains("prior summary");
    }

    @Test
    void findSummary_whenNoneExists_returnsEmpty() {
        when(summaryRepo.findSummaryByAvatarId("avatar-1")).thenReturn(Optional.empty());

        Optional<String> result = summariser.findSummary("avatar-1");

        assertThat(result).isEmpty();
    }

    @Test
    void updateSummary_blankUserMessage_doesNotCallGemini() {
        summariser.updateSummary("avatar-1", "  ", "assistant reply");

        verifyNoInteractions(geminiCompletion);
        verifyNoInteractions(summaryRepo);
    }

    @Test
    void updateSummary_blankAssistantReply_doesNotCallGemini() {
        summariser.updateSummary("avatar-1", "user message", "");

        verifyNoInteractions(geminiCompletion);
        verifyNoInteractions(summaryRepo);
    }

    @Test
    void updateSummary_validMessages_callsUpsertWithTrimmedSummary() {
        when(summaryRepo.findSummaryByAvatarId("avatar-1")).thenReturn(Optional.empty());
        when(geminiCompletion.complete(anyInt(), anyString(), anyString()))
                .thenReturn("  updated summary  ");

        summariser.updateSummary("avatar-1", "user msg", "assistant reply");

        verify(summaryRepo).upsertSummary(eq("avatar-1"), eq("updated summary"), any());
    }

    @Test
    void updateSummary_geminiReturnsBlank_doesNotUpsert() {
        when(summaryRepo.findSummaryByAvatarId("avatar-1")).thenReturn(Optional.empty());
        when(geminiCompletion.complete(anyInt(), anyString(), anyString())).thenReturn("");

        summariser.updateSummary("avatar-1", "user msg", "assistant reply");

        verify(summaryRepo, never()).upsertSummary(any(), any(), any());
    }

    @Test
    void updateSummary_geminiThrows_swallowsExceptionAndDoesNotUpsert() {
        when(summaryRepo.findSummaryByAvatarId("avatar-1")).thenReturn(Optional.empty());
        when(geminiCompletion.complete(anyInt(), anyString(), anyString()))
                .thenThrow(new RuntimeException("AI failure"));

        // must not propagate
        summariser.updateSummary("avatar-1", "user msg", "assistant reply");

        verify(summaryRepo, never()).upsertSummary(any(), any(), any());
    }
}
