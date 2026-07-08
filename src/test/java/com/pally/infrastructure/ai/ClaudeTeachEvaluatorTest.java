package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.api.teach.dto.TeachResponse;
import com.pally.domain.knowledge.WikiPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Teach evaluator = the 4th fail-open sibling. A parse/blank/exception must NOT
 * become a 0/0/0 success shape — it's EVAL_FAILED so the client shows a retry,
 * never a false "you covered 0 concepts" score card.
 */
@ExtendWith(MockitoExtension.class)
class ClaudeTeachEvaluatorTest {

    @Mock GeminiCompletionService geminiCompletion;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ClaudeTeachEvaluator evaluator() {
        return new ClaudeTeachEvaluator(geminiCompletion, objectMapper);
    }

    private WikiPage page() {
        return WikiPage.create("avatar-1", "photosynthesis", "Photosynthesis",
                "Plants convert light to energy.");
    }

    @Test
    void invalidJson_returnsEvalFailed_notZeroZeroScore() throws Exception {
        when(geminiCompletion.complete(anyInt(), anyString(), eq("teach-eval"), anyString()))
                .thenReturn("I can't produce JSON right now, sorry");

        TeachResponse r = evaluator().evaluate(page(), "Photosynthesis turns light into sugar in the leaves.");

        assertThat(r.status()).isEqualTo(TeachResponse.Status.EVAL_FAILED);
        assertThat(r.xpEarned()).isZero();          // nothing to award
        assertThat(r.totalConcepts()).isZero();     // no certainty signal (guarded)
    }

    @Test
    void blankResponse_returnsEvalFailed() throws Exception {
        when(geminiCompletion.complete(anyInt(), anyString(), eq("teach-eval"), anyString()))
                .thenReturn("   ");
        TeachResponse r = evaluator().evaluate(page(), "A perfectly long explanation of the topic.");
        assertThat(r.status()).isEqualTo(TeachResponse.Status.EVAL_FAILED);
    }

    @Test
    void tooShortExplanation_returnsEvalFailed_withoutCallingLLM() {
        TeachResponse r = evaluator().evaluate(page(), "ok");
        assertThat(r.status()).isEqualTo(TeachResponse.Status.EVAL_FAILED);
        verifyNoInteractions(geminiCompletion);
    }

    @Test
    void validResponse_returnsOk_withRealScore() throws Exception {
        when(geminiCompletion.complete(anyInt(), anyString(), eq("teach-eval"), anyString()))
                .thenReturn("{\"covered\":[\"light\",\"sugar\"],\"missed\":[],\"feedback\":\"Great!\"}");
        TeachResponse r = evaluator().evaluate(page(), "Photosynthesis turns light into sugar in the leaves.");
        assertThat(r.status()).isEqualTo(TeachResponse.Status.OK);
        assertThat(r.score()).isEqualTo(2);
        assertThat(r.totalConcepts()).isEqualTo(2);
        // Attribution: the avatar is threaded to the cost seam (record(...avatarId)).
        org.mockito.Mockito.verify(geminiCompletion)
                .complete(anyInt(), anyString(), eq("teach-eval"), eq("avatar-1"));
    }
}
