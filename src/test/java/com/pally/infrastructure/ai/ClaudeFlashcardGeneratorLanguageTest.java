package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.quiz.FlashcardRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Byte-identical-English guard for flashcard generation (Phase 1b.3b). The language follows the
 * PAGE the cards are derived from (1b.5a tagged it) — the material's language, not the reader's.
 * useGemini defaults false (@Value), so the bare instance routes through claudeApiClient; we capture
 * that prompt. Asserts zh == en + directive.
 */
class ClaudeFlashcardGeneratorLanguageTest {

    private final ClaudeApiClient claude = mock(ClaudeApiClient.class);
    private final GeminiCompletionService gemini = mock(GeminiCompletionService.class);
    private final ModelRouter modelRouter = mock(ModelRouter.class);
    private final FlashcardRepository flashcardRepository = mock(FlashcardRepository.class);
    private final ClaudeFlashcardGenerator gen =
            new ClaudeFlashcardGenerator(claude, gemini, new ObjectMapper(), modelRouter, flashcardRepository);

    private String promptForPageLanguage(String pageLang) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        when(modelRouter.getHaikuModel()).thenReturn("haiku");
        // claudeApiClient.complete(model, maxTokens, prompt, purpose) — prompt is the 3rd arg.
        when(claude.complete(anyString(), anyInt(), captor.capture(), anyString())).thenReturn("[]");
        WikiPage page = WikiPage.create("av", "slug", "Photosynthesis", "Plants make food from light.");
        page.setContentLanguage(pageLang);
        gen.generateAndSaveForPage("av", page);
        return captor.getValue();
    }

    @Test
    void englishByteIdentical_zhFollowsThePage() {
        String en = promptForPageLanguage("en");
        String zh = promptForPageLanguage("zh");
        assertThat(en).doesNotContain("华语");
        assertThat(zh).isEqualTo(en + PromptLanguage.directive("zh"));
        assertThat(zh).contains("华语");
    }
}
