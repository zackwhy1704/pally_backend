package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.KnowledgeFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
                .doesNotContain("knowledge organiser for a student study app");
    }

    @Test
    void normalAvatar_keepsStudyNotesPrompt() {
        String prompt = capturePromptFor(false);
        assertThat(prompt)
                .contains("knowledge organiser for a student study app")
                .doesNotContain("MARKING STANDARD");
    }
}
