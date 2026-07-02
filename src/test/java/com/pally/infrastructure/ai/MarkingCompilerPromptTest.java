package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.port.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A MARKING_CORPUS avatar must compile with the marking-behaviour prompt (learn
 * HOW the teacher marks), while a normal avatar keeps the study-notes prompt.
 */
class MarkingCompilerPromptTest {

    private ClaudeWikiCompiler compiler;
    private ClaudeApiClient mockApiClient;

    @BeforeEach
    void setUp() {
        mockApiClient = mock(ClaudeApiClient.class);
        ModelRouter modelRouter = mock(ModelRouter.class);
        when(modelRouter.forWikiCompile()).thenReturn("claude-haiku-4-5");
        when(mockApiClient.complete(anyString(), anyInt(), anyString())).thenReturn("[]");
        compiler = new ClaudeWikiCompiler(mockApiClient, new ObjectMapper(), modelRouter);
    }

    private String capturePromptFor(boolean markingCorpus) {
        Avatar a = mock(Avatar.class);
        when(a.getId()).thenReturn("av-1");
        when(a.getName()).thenReturn("Marking");
        when(a.getSubject()).thenReturn(Subject.MATHS);
        when(a.isMarkingCorpus()).thenReturn(markingCorpus);

        KnowledgeFile f = mock(KnowledgeFile.class);
        when(f.getFileName()).thenReturn("rubric.pdf");
        when(f.getExtractedText()).thenReturn("Award 1 method mark for correct formula.");

        compiler.compile(a, List.of(f), List.of());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mockApiClient).complete(anyString(), anyInt(), captor.capture());
        return captor.getValue();
    }

    @Test
    void markingCorpusAvatar_usesMarkingBehaviourPrompt() {
        String prompt = capturePromptFor(true);
        assertThat(prompt)
                .contains("MARKING STANDARD")
                .contains("MARKING-BEHAVIOUR")
                .contains("How marks are awarded")
                .containsIgnoringCase("context") // contextual chunk summary requested
                .doesNotContain("knowledge organiser for a student study app");
    }

    @Test
    void normalAvatar_keepsStudyNotesPrompt() {
        String prompt = capturePromptFor(false);
        assertThat(prompt)
                .contains("knowledge organiser for a student study app")
                .containsIgnoringCase("context") // contextual chunk summary requested
                .doesNotContain("MARKING STANDARD");
    }

    @Test
    void weaknessProfileAvatar_usesWeaknessProfilePrompt() {
        Avatar a = mock(Avatar.class);
        when(a.getId()).thenReturn("wk-1");
        when(a.getName()).thenReturn("Weakness");
        when(a.getSubject()).thenReturn(Subject.MATHS);
        when(a.isMarkingCorpus()).thenReturn(false);
        when(a.isWeaknessProfile()).thenReturn(true);
        KnowledgeFile f = mock(KnowledgeFile.class);
        when(f.getFileName()).thenReturn("performance-signals");
        when(f.getExtractedText()).thenReturn("Weak: fractions 1/5 correct.");

        compiler.compile(a, List.of(f), List.of());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mockApiClient).complete(anyString(), anyInt(), captor.capture());
        String prompt = captor.getValue();
        assertThat(prompt)
                .contains("WEAKNESS PROFILE")
                .containsIgnoringCase("performance signals")
                .doesNotContain("MARKING STANDARD")
                .doesNotContain("knowledge organiser for a student study app");
    }

    @Test
    void compile_parsesTheContextFieldIntoTheDraft() {
        when(mockApiClient.complete(anyString(), anyInt(), anyString())).thenReturn(
                "[{\"slug\":\"s\",\"title\":\"T\",\"content\":\"Body.\","
                + "\"context\":\"Covers X within Maths.\",\"prerequisites\":[]}]");
        Avatar a = mock(Avatar.class);
        when(a.getId()).thenReturn("av-1");
        when(a.getName()).thenReturn("Zap");
        when(a.getSubject()).thenReturn(Subject.MATHS);
        when(a.isMarkingCorpus()).thenReturn(false);
        KnowledgeFile f = mock(KnowledgeFile.class);
        when(f.getFileName()).thenReturn("n.txt");
        when(f.getExtractedText()).thenReturn("some source text");

        var drafts = compiler.compile(a, List.of(f), List.of());

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).context()).isEqualTo("Covers X within Maths.");
    }

    /**
     * The marking prompt must be ONE definition — the Gemini (primary) and Claude
     * (fallback) compilers must emit an identical marking header, so the compiled
     * marking standard can't drift by which tier served the compile.
     */
    @Test
    void markingPrompt_isIdenticalAcrossGeminiAndClaude() throws Exception {
        ClaudeWikiCompiler claude =
                new ClaudeWikiCompiler(mock(ClaudeApiClient.class), new ObjectMapper(), mock(ModelRouter.class));
        GeminiWikiCompiler gemini =
                new GeminiWikiCompiler(mock(WebClient.class), new ObjectMapper(), claude, mock(StoragePort.class));

        Avatar a = mock(Avatar.class);
        when(a.getSubject()).thenReturn(Subject.MATHS);

        String claudeHeader = markingHeaderOf(claude, a);
        String geminiHeader = markingHeaderOf(gemini, a);

        assertThat(geminiHeader).isEqualTo(claudeHeader);
        // ...and both come from the single shared source.
        assertThat(claudeHeader).isEqualTo(WikiCompilerPrompts.markingHeader("Maths"));
    }

    private static String markingHeaderOf(Object compiler, Avatar a) throws Exception {
        Method m = compiler.getClass().getDeclaredMethod("markingPromptHeader", Avatar.class);
        m.setAccessible(true);
        return (String) m.invoke(compiler, a);
    }
}
