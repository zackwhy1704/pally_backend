package com.pally.domain.marking;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The FEED side (Part 3). Proves the loop closes AND is debounced/bounded:
 *   - below threshold → no recompile (the cost-saving debounce);
 *   - at threshold → the corrections are folded into the marking-wiki (via the
 *     ingest harness) and marked compiled;
 *   - the fed source carries the actual deltas, newest first (recency);
 *   - a compile failure leaves the batch UNcompiled (retried, not lost).
 */
@ExtendWith(MockitoExtension.class)
class MarkingCorrectionCompilerTest {

    @Mock MarkingCorrectionRepository repository;
    @Mock MarkingIngestService ingestService;

    private static final String CLASS = "class-1";
    private static final int THRESHOLD = 5;

    private MarkingCorrectionCompiler compiler() {
        return new MarkingCorrectionCompiler(repository, ingestService, THRESHOLD, 20);
    }

    private MarkingCorrection correction(String id, String aiGrade, String teacherGrade,
                                         String aiFb, String teacherFb, Instant at) {
        return new MarkingCorrection(id, "sub-" + id, CLASS, "MATHS",
                aiGrade, teacherGrade, aiFb, teacherFb, at, null);
    }

    private List<MarkingCorrection> nUncompiled(int n) {
        return IntStream.range(0, n)
                .mapToObj(i -> correction("c" + i, "A", "C", "Good", "Wrong, see working",
                        Instant.parse("2026-07-0" + (i + 1) + "T00:00:00Z")))
                .toList();
    }

    @Test
    void belowThreshold_doesNotRecompile_theDebounce() {
        when(repository.findUncompiledByClassId(CLASS)).thenReturn(nUncompiled(THRESHOLD - 1));
        int consumed = compiler().recompileClassIfDue(CLASS);
        assertThat(consumed).isZero();
        verify(ingestService, never()).ingestFiles(any(), anyList());
        verify(repository, never()).markCompiled(anyList(), any());
    }

    @Test
    void atThreshold_foldsCorrectionsIntoMarkingWiki_andMarksThemCompiled() {
        List<MarkingCorrection> batch = nUncompiled(THRESHOLD);
        when(repository.findUncompiledByClassId(CLASS)).thenReturn(batch);

        int consumed = compiler().recompileClassIfDue(CLASS);

        assertThat(consumed).isEqualTo(THRESHOLD);
        // Fed into the marking compile harness as ONE text source carrying the deltas.
        ArgumentCaptor<List<IncomingFile>> files = ArgumentCaptor.forClass(List.class);
        verify(ingestService).ingestFiles(eq(CLASS), files.capture());
        String text = new String(files.getValue().get(0).bytes(), StandardCharsets.UTF_8);
        assertThat(text)
                .as("the fed source states how the teacher corrected the AI")
                .contains("TEACHER MARKING CORRECTIONS")
                .contains("the AI suggested A")
                .contains("the teacher awarded C");
        // Recency: newest correction (2026-07-05) appears before the oldest (2026-07-01).
        assertThat(text.indexOf("2026-07-05")).isLessThan(text.indexOf("2026-07-01"));
        // The whole batch is marked compiled so it won't re-trigger.
        ArgumentCaptor<List<String>> ids = ArgumentCaptor.forClass(List.class);
        verify(repository).markCompiled(ids.capture(), any(Instant.class));
        assertThat(ids.getValue()).hasSize(THRESHOLD);
    }

    @Test
    void compileFailure_leavesBatchUncompiled_soItRetries() {
        when(repository.findUncompiledByClassId(CLASS)).thenReturn(nUncompiled(THRESHOLD));
        doThrow(new RuntimeException("compile down")).when(ingestService).ingestFiles(any(), anyList());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> compiler().recompileClassIfDue(CLASS))
                .isInstanceOf(RuntimeException.class);
        // Never marked compiled → the corrections survive for the next trigger.
        verify(repository, never()).markCompiled(anyList(), any());
    }

    @Test
    void asyncTrigger_swallowsFailures_soCaptureIsNeverAffected() {
        when(repository.findUncompiledByClassId(CLASS)).thenReturn(nUncompiled(THRESHOLD));
        doThrow(new RuntimeException("compile down")).when(ingestService).ingestFiles(any(), anyList());
        // The event handler must not propagate (capture/release is upstream of it).
        org.assertj.core.api.Assertions.assertThatCode(() ->
                compiler().onCorrectionCaptured(new MarkingCorrectionCapturedEvent(CLASS)))
                .doesNotThrowAnyException();
    }
}
