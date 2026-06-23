package com.pally.domain.centre;

import com.pally.infrastructure.ai.ClaudeApiClient;
import com.pally.infrastructure.ai.ModelRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The async generator must ALWAYS reach a terminal persisted state — ready on
 * success (including the valid "no data" case) or failed on error — and must
 * never let an exception escape (nothing awaits it).
 */
@ExtendWith(MockitoExtension.class)
class ClassReportGeneratorTest {

    private static final String USER = "u1", ORG = "o1", CLASS = "c1";

    @Mock ContentReviewService contentReviewService;
    @Mock ClaudeApiClient claudeClient;
    @Mock ModelRouter modelRouter;
    @Mock ClassReportStore store;

    @InjectMocks ClassReportGenerator generator;

    private void stubConcepts() {
        when(contentReviewService.examReadiness(USER, ORG, CLASS)).thenReturn(Map.of(
                "concepts", List.of(Map.of(
                        "concept", "Fractions", "avgMastery", 62, "studentsBelowCount", 3))));
    }

    @Test
    void generate_success_marksReadyWithNarrative() {
        stubConcepts();
        when(modelRouter.getHaikuModel()).thenReturn("haiku");
        when(claudeClient.complete(eq("haiku"), anyInt(), any(), eq("class-report")))
                .thenReturn("The class is strong on fractions.");

        generator.generate(USER, ORG, CLASS);

        verify(store).markReady(eq(CLASS), contains("strong on fractions"), any());
        verify(store, never()).markFailed(any(), any());
    }

    @Test
    void generate_noQuizData_marksReadyWithGuidanceMessage_noClaudeCall() {
        when(contentReviewService.examReadiness(USER, ORG, CLASS))
                .thenReturn(Map.of("concepts", List.of()));

        generator.generate(USER, ORG, CLASS);

        verify(store).markReady(eq(CLASS), contains("No quiz data yet"), any());
        verify(claudeClient, never()).complete(any(), anyInt(), any(), any());
    }

    @Test
    void generate_claudeThrows_marksFailed_doesNotPropagate() {
        stubConcepts();
        when(modelRouter.getHaikuModel()).thenReturn("haiku");
        when(claudeClient.complete(any(), anyInt(), any(), any()))
                .thenThrow(new RuntimeException("claude 503"));

        generator.generate(USER, ORG, CLASS); // must not throw

        verify(store).markFailed(eq(CLASS), any());
        verify(store, never()).markReady(any(), any(), any());
    }
}
