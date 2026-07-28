package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.knowledge.WikiPage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Byte-identical-English guard for teach-eval (Phase 1b.3b). The language follows the PAGE the
 * student is teaching back (1b.5a tagged it). Captures the prompt; asserts zh == en + directive.
 */
class ClaudeTeachEvaluatorLanguageTest {

    private final GeminiCompletionService gemini = mock(GeminiCompletionService.class);
    private final ClaudeTeachEvaluator evaluator = new ClaudeTeachEvaluator(gemini, new ObjectMapper());

    private String promptForPageLanguage(String pageLang) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        when(gemini.complete(anyInt(), captor.capture(), anyString(), anyString())).thenReturn("{}");
        WikiPage page = WikiPage.create("av", "slug", "Photosynthesis", "Plants make food from light.");
        page.setContentLanguage(pageLang);
        evaluator.evaluate(page, "Plants use sunlight to make sugar.");
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
