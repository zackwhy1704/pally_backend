package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.knowledge.RelevanceScore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the relevance-check fail-direction consistency: BOTH "checker unavailable" paths
 * — an API exception AND a malformed-but-200 body — must fail OPEN (accept the upload).
 * A false reject (0.0) silently discards a student's valid upload (its pages get dropped).
 */
@ExtendWith(MockitoExtension.class)
class ClaudeRelevanceCheckerTest {

    @Mock ClaudeApiClient apiClient;
    @Mock ModelRouter modelRouter;

    ClaudeRelevanceChecker checker;

    @BeforeEach
    void setUp() {
        checker = new ClaudeRelevanceChecker(apiClient, new ObjectMapper(), modelRouter);
        lenient().when(modelRouter.forRelevanceCheck()).thenReturn("claude-relevance");
    }

    @Test
    void malformedResponse_failsOpen_acceptsUpload() {
        // A 200 with an unparseable body — the checker being unavailable, not a signal.
        when(apiClient.complete(anyString(), anyInt(), anyString()))
                .thenReturn("this is definitely not the JSON we asked for");

        RelevanceScore score = checker.check("Maths", "", "quadratic equations notes");

        assertThat(score.value()).isEqualTo(1.0); // fail-OPEN (was 0.0 reject) — upload kept
    }

    @Test
    void apiException_alsoFailsOpen_sameDirection() {
        when(apiClient.complete(anyString(), anyInt(), anyString()))
                .thenThrow(new RuntimeException("Claude down"));

        RelevanceScore score = checker.check("Maths", "", "quadratic equations notes");

        assertThat(score.value()).isEqualTo(1.0);
    }

    @Test
    void prompt_pinsNonEducationalCriteria_andAntiContaminationGuard() {
        // F2: explicit non-educational document criteria + a minimum-substance bar
        // so a receipt/invoice/form is classified studyMaterial=false.
        // F3: the "reason" must describe only the NEW content, never the existing
        // knowledge summary (the cross-file "Kopi Corner receipt" bleed).
        when(apiClient.complete(anyString(), anyInt(), anyString()))
                .thenReturn("{\"score\":0.9,\"reason\":\"ok\",\"studyMaterial\":true}");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);

        checker.check("Science", "- Kopi Corner receipt", "photosynthesis notes");

        verify(apiClient).complete(anyString(), anyInt(), prompt.capture());
        String p = prompt.getValue();
        assertThat(p).containsIgnoringCase("receipt");
        assertThat(p).containsIgnoringCase("invoice");
        assertThat(p).containsIgnoringCase("form");
        assertThat(p).containsIgnoringCase("instructional material"); // min-substance bar
        assertThat(p).contains("must describe ONLY");                 // F3 anti-contamination
    }
}
