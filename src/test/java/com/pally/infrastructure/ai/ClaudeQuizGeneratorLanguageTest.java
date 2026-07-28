package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.knowledge.WikiPage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Byte-identical-English guard for quiz generation (Phase 1b.3). Mocks the Claude client and
 * captures the actual prompt; the assertion is the same portable invariant —
 * zhPrompt == enPrompt + directive("zh"), en free of Chinese. We only assert on the captured
 * prompt, so it holds even though generate() throws afterwards on the empty stubbed response.
 */
class ClaudeQuizGeneratorLanguageTest {

    private final ClaudeApiClient claude = mock(ClaudeApiClient.class);
    private final ModelRouter modelRouter = mock(ModelRouter.class);
    private final ClaudeQuizGenerator gen =
            new ClaudeQuizGenerator(claude, new ObjectMapper(), modelRouter, null, null);

    private final List<WikiPage> pages =
            List.of(WikiPage.create("av", "fractions", "Fractions", "A fraction is part of a whole."));

    private String promptFor(String lang) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        when(modelRouter.forQuizGeneration()).thenReturn("haiku");
        when(claude.complete(anyString(), anyInt(), captor.capture(), anyString())).thenReturn("[]");
        try {
            gen.generate("av", pages, lang);
        } catch (RuntimeException ignored) {
            // Empty stub → generate() may throw after the prompt is already captured; we only
            // assert on the captured prompt.
        }
        return captor.getValue();
    }

    @Test
    void englishQuizPrompt_isByteIdenticalBase_zhIsBasePlusDirectiveOnly() {
        String en = promptFor("en");
        String zh = promptFor("zh");
        assertThat(en).doesNotContain("华语");
        assertThat(zh).isEqualTo(en + PromptLanguage.directive("zh"));
        assertThat(zh).contains("华语");
    }
}
