package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.quiz.FlashCard;
import com.pally.domain.quiz.FlashcardRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins the flashcard model-flip routing: the {@code flashcard.use-gemini} flag chooses
 * the provider, both paths ledger under the same "flashcard-gen" purpose (so Haiku and
 * Gemini costs are directly comparable), and cards still parse+save the same way.
 */
@ExtendWith(MockitoExtension.class)
class ClaudeFlashcardGeneratorTest {

    @Mock ClaudeApiClient claudeApiClient;
    @Mock GeminiCompletionService geminiCompletion;
    @Mock ModelRouter modelRouter;
    @Mock FlashcardRepository flashcardRepository;

    private static final String CARDS_JSON =
            "[{\"front\":\"What is 2+2?\",\"back\":\"4\"},"
            + "{\"front\":\"Capital of France?\",\"back\":\"Paris\"}]";

    private ClaudeFlashcardGenerator generator(boolean useGemini) {
        ClaudeFlashcardGenerator g = new ClaudeFlashcardGenerator(
                claudeApiClient, geminiCompletion, new ObjectMapper(),
                modelRouter, flashcardRepository);
        ReflectionTestUtils.setField(g, "useGemini", useGemini);
        return g;
    }

    private WikiPage page() {
        return WikiPage.create("av-1", "fractions", "Fractions", "A fraction is part of a whole.");
    }

    @Test
    void flagOff_routesToHaiku_underFlashcardGenPurpose_notGemini() {
        lenient().when(modelRouter.getHaikuModel()).thenReturn("claude-haiku-4-5");
        when(claudeApiClient.complete(eq("claude-haiku-4-5"), anyInt(), any(), eq("flashcard-gen")))
                .thenReturn(CARDS_JSON);

        generator(false).generateAndSaveForPage("av-1", page());

        verify(claudeApiClient).complete(eq("claude-haiku-4-5"), anyInt(), any(), eq("flashcard-gen"));
        verifyNoInteractions(geminiCompletion);

        ArgumentCaptor<List<FlashCard>> captor = ArgumentCaptor.forClass(List.class);
        verify(flashcardRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    void flagOn_routesToGemini_withFlashcardGenPurposeAndAvatarId_notHaiku() {
        when(geminiCompletion.complete(anyInt(), any(), eq("flashcard-gen"), eq("av-1")))
                .thenReturn(CARDS_JSON);

        generator(true).generateAndSaveForPage("av-1", page());

        verify(geminiCompletion).complete(anyInt(), any(), eq("flashcard-gen"), eq("av-1"));
        verify(claudeApiClient, never()).complete(any(), anyInt(), any());
        verify(claudeApiClient, never()).complete(any(), anyInt(), any(), any());
        verify(flashcardRepository).saveAll(any());
    }

    @Test
    void blankContent_generatesNothing_callsNoModel() {
        generator(true).generateAndSaveForPage(
                "av-1", WikiPage.create("av-1", "empty", "Empty", "   "));

        verifyNoInteractions(geminiCompletion);
        verifyNoInteractions(claudeApiClient);
        verify(flashcardRepository, never()).saveAll(any());
    }
}
