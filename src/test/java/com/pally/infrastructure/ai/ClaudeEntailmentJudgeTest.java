package com.pally.infrastructure.ai;

import com.pally.domain.knowledge.groundedness.Claim;
import com.pally.domain.knowledge.groundedness.EntailmentJudge.Entailment;
import com.pally.domain.knowledge.groundedness.EntailmentJudge.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pins the P3 doctrine flip: the entailment judge now RETRIES once and then fails
 * CLOSED (unverifiable hard facts → NOT_IN_SOURCE → flagged for teacher review),
 * where it used to fail OPEN (silently pass every claim on a judge outage). Safe
 * only because #1/#2 removed the false-positive flood that would over-reject.
 */
@ExtendWith(MockitoExtension.class)
class ClaudeEntailmentJudgeTest {

    @Mock private ClaudeApiClient claude;
    @Mock private ModelRouter modelRouter;
    private ClaudeEntailmentJudge judge;

    private final List<Claim> claims =
            List.of(new Claim("Water boils at 100 degrees.", true),
                    new Claim("The sky is green.", true));

    @BeforeEach
    void setUp() {
        judge = new ClaudeEntailmentJudge(claude, modelRouter);
        lenient().when(modelRouter.getHaikuModel()).thenReturn("haiku");
    }

    @Test
    void unparseableResponse_retriesOnce_thenFailsClosed() {
        when(claude.complete(anyString(), anyInt(), anyString(), eq("groundedness")))
                .thenReturn("sorry, I can't do that"); // no JSON array, both attempts

        List<Entailment> out = judge.judge("src", claims);

        // Retried once (two calls), then failed CLOSED — every claim NOT_IN_SOURCE
        // so its hard facts get FLAGGED, never silently SUPPORTED.
        verify(claude, times(2)).complete(anyString(), anyInt(), anyString(), eq("groundedness"));
        assertThat(out).hasSize(2);
        assertThat(out).allMatch(e -> e.verdict() == Verdict.NOT_IN_SOURCE);
    }

    @Test
    void callThrowsThenValid_retrySucceeds_noFailClosed() {
        when(claude.complete(anyString(), anyInt(), anyString(), eq("groundedness")))
                .thenThrow(new RuntimeException("503"))
                .thenReturn("[{\"verdict\":\"SUPPORTED\",\"sourceQuote\":\"q\"},"
                        + "{\"verdict\":\"CONTRADICTED\",\"sourceQuote\":\"q2\"}]");

        List<Entailment> out = judge.judge("src", claims);

        verify(claude, times(2)).complete(anyString(), anyInt(), anyString(), eq("groundedness"));
        assertThat(out.get(0).verdict()).isEqualTo(Verdict.SUPPORTED);
        assertThat(out.get(1).verdict()).isEqualTo(Verdict.CONTRADICTED);
    }

    @Test
    void validResponseFirstTry_isNotRetried() {
        when(claude.complete(anyString(), anyInt(), anyString(), eq("groundedness")))
                .thenReturn("[{\"verdict\":\"SUPPORTED\",\"sourceQuote\":\"q\"},"
                        + "{\"verdict\":\"NOT_IN_SOURCE\",\"sourceQuote\":null}]");

        List<Entailment> out = judge.judge("src", claims);

        verify(claude, times(1)).complete(anyString(), anyInt(), anyString(), eq("groundedness"));
        assertThat(out.get(0).verdict()).isEqualTo(Verdict.SUPPORTED);
        assertThat(out.get(1).verdict()).isEqualTo(Verdict.NOT_IN_SOURCE);
    }

    @Test
    void shortfallResponse_padsClosed_notOpen() {
        // Model echoes only 1 of 2 claims → the un-echoed claim is unverified, so it
        // pads NOT_IN_SOURCE (fail closed), never SUPPORTED.
        when(claude.complete(anyString(), anyInt(), anyString(), eq("groundedness")))
                .thenReturn("[{\"verdict\":\"SUPPORTED\",\"sourceQuote\":\"q\"}]");

        List<Entailment> out = judge.judge("src", claims);

        assertThat(out).hasSize(2);
        assertThat(out.get(1).verdict()).isEqualTo(Verdict.NOT_IN_SOURCE);
    }
}
