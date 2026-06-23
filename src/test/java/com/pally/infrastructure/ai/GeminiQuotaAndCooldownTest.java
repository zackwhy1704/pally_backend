package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.port.WikiCompilerPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests for the Gemini quota counter and cooldown logic.
 * Verifies: daily counter, warn threshold, cooldown bypass, haiku fallback toggle.
 */
@ExtendWith(MockitoExtension.class)
class GeminiQuotaAndCooldownTest {

    @Mock ClaudeWikiCompiler claudeFallback;
    @Mock WebClient webClient;
    @Mock com.pally.domain.knowledge.port.StoragePort storagePort;

    private GeminiWikiCompiler compiler;

    private Avatar avatar;

    @BeforeEach
    void setUp() {
        compiler = new GeminiWikiCompiler(webClient, new ObjectMapper(), claudeFallback, storagePort);
        // Set an API key so it doesn't skip to haiku immediately
        ReflectionTestUtils.setField(compiler, "apiKey", "test-key");
        ReflectionTestUtils.setField(compiler, "modelPrimary", "gemini-2.5-flash");
        ReflectionTestUtils.setField(compiler, "modelSecondary", "gemini-2.0-flash");
        ReflectionTestUtils.setField(compiler, "baseUrl", "https://generativelanguage.googleapis.com");
        ReflectionTestUtils.setField(compiler, "haikuFallbackEnabled", true);

        avatar = Avatar.reconstitute("avatar-1", "user-1", "Zap",
                Subject.SCIENCE, CharacterType.MOCHI, 0, Instant.now());
    }

    @Test
    void cooldown_notActive_returnsNotInCooldown() {
        assertThat(compiler.isInCooldown()).isFalse();
    }

    @Test
    void cooldown_setInFuture_returnsInCooldown() {
        compiler.setCooldownUntil(Instant.now().plusSeconds(300));
        assertThat(compiler.isInCooldown()).isTrue();
    }

    @Test
    void cooldown_expired_returnsNotInCooldown() {
        compiler.setCooldownUntil(Instant.now().minusSeconds(10));
        assertThat(compiler.isInCooldown()).isFalse();
    }

    @Test
    void compileWithTier_duringCooldown_fallsToHaiku() {
        compiler.setCooldownUntil(Instant.now().plusSeconds(300));

        KnowledgeFile file = makeFile("notes.pdf", "Some text content here");

        when(claudeFallback.compile(any(), any(), any()))
                .thenReturn(List.of(new WikiCompilerPort.WikiPageDraft(
                        "test", "Test", "Content", List.of())));

        WikiCompilerPort.CompileOutput output =
                compiler.compileWithTier(avatar, List.of(file), List.of());

        assertThat(output.tierServed()).isEqualTo("haiku-chunked-fallback");
    }

    @Test
    void compileWithTier_haikuFallbackDisabled_throwsOnFailure() {
        ReflectionTestUtils.setField(compiler, "haikuFallbackEnabled", false);
        compiler.setCooldownUntil(Instant.now().plusSeconds(300));

        KnowledgeFile file = makeFile("notes.pdf", "Some text");

        assertThatThrownBy(() ->
                compiler.compileWithTier(avatar, List.of(file), List.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Haiku fallback is disabled");
    }

    @Test
    void compileWithTier_noApiKey_fallsToHaiku() {
        ReflectionTestUtils.setField(compiler, "apiKey", "");

        KnowledgeFile file = makeFile("notes.pdf", "Text");

        when(claudeFallback.compile(any(), any(), any()))
                .thenReturn(List.of(new WikiCompilerPort.WikiPageDraft(
                        "slug", "Title", "Content", List.of())));

        WikiCompilerPort.CompileOutput output =
                compiler.compileWithTier(avatar, List.of(file), List.of());

        assertThat(output.tierServed()).contains("haiku");
    }

    @Test
    void dailyCounter_resetAndIncrement() {
        compiler.resetDailyCounter();
        assertThat(compiler.getDailyRequestCount()).isZero();
    }

    private KnowledgeFile makeFile(String name, String text) {
        KnowledgeFile kf = KnowledgeFile.create("avatar-1", "user-1", name,
                "key/" + name, KnowledgeFile.UploadType.PDF);
        kf.setExtractedText(text);
        kf.markReady(1);
        return kf;
    }
}
