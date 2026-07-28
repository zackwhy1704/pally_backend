package com.pally.domain.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.infrastructure.ai.GeminiCompletionService;
import com.pally.infrastructure.ai.PromptLanguage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Byte-identical-English guard for the four module generators (Phase 1b.2), exercising the REAL
 * path (generate*→prompt→GeminiCompletionService.complete) with a mocked completion service that
 * captures the prompt. Same invariant as the wiki guard: for each generator the zh prompt equals
 * the en prompt plus exactly the appended directive, and the en prompt carries no Chinese.
 * Fails without the tail append; fails if any generator's English branch grows its own language text.
 */
class ModuleContentGeneratorLanguageTest {

    private final GeminiCompletionService gemini = mock(GeminiCompletionService.class);
    // generate* touch only geminiCompletion + objectMapper; the other collaborators are unused here.
    private final ModuleContentGenerator gen =
            new ModuleContentGenerator(gemini, new ObjectMapper(), null, null, null);

    /** Runs one generator invocation for the given content_language and returns the prompt it sent. */
    private String promptFor(Consumer<String> invokeWithLang, String lang) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        when(gemini.complete(anyInt(), captor.capture(), anyString(), anyString()))
                .thenReturn("[{\"k\":\"v\"}]");
        invokeWithLang.accept(lang);
        return captor.getValue();
    }

    private void assertByteIdenticalEn(Consumer<String> generator) {
        String en = promptFor(generator, "en");
        String zh = promptFor(generator, "zh");
        assertThat(en).doesNotContain("华语");
        assertThat(zh).isEqualTo(en + PromptLanguage.directive("zh"));
        assertThat(zh).contains("华语");
    }

    @Test
    void microCards_englishByteIdentical_zhAppendsDirective() {
        assertByteIdenticalEn(lang ->
                gen.generateMicroCards("m", "content", "a student", "Science", "FREE", "", "av", lang));
    }

    @Test
    void hotTakes_englishByteIdentical_zhAppendsDirective() {
        assertByteIdenticalEn(lang ->
                gen.generateHotTakes("m", "content", "a student", "Science", "FREE", "", "av", lang));
    }

    @Test
    void spotMistake_englishByteIdentical_zhAppendsDirective() {
        assertByteIdenticalEn(lang ->
                gen.generateSpotMistake("m", "content", "a student", "Science", "", "av", lang));
    }

    @Test
    void challenges_englishByteIdentical_zhAppendsDirective() {
        assertByteIdenticalEn(lang ->
                gen.generateChallenges("m", "content", "a student", "Science", "FREE", "", "av", lang));
    }
}
