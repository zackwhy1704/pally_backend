package com.pally.domain.centre;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The class-report GET must never block on Claude — it returns a fast status and
 * only DISPATCHES async generation, never runs it inline. These tests pin the
 * cache/dispatch decision table.
 */
@ExtendWith(MockitoExtension.class)
class ClassReportServiceTest {

    private static final String USER = "u1", ORG = "o1", CLASS = "c1";

    @Mock CentreAccessService centreAccessService;
    @Mock ClassReportStore store;
    @Mock ClassReportGenerator generator;

    @InjectMocks ClassReportService service;

    @Test
    void getReport_noRow_dispatchesGeneration_andReturnsGenerating() {
        when(store.find(CLASS)).thenReturn(Optional.empty());

        Map<String, Object> out = service.getReport(USER, ORG, CLASS);

        assertThat(out.get("status")).isEqualTo("generating");
        verify(store).markGenerating(CLASS);
        verify(generator).generate(USER, ORG, CLASS);
    }

    @Test
    void getReport_readyAndFresh_returnsCachedNarrative_withoutDispatch() {
        var row = new ClassReportStore.StoredReport(CLASS, "All good.", "ready",
                null, Instant.now().minus(Duration.ofMinutes(5)), Instant.now());
        when(store.find(CLASS)).thenReturn(Optional.of(row));

        Map<String, Object> out = service.getReport(USER, ORG, CLASS);

        assertThat(out.get("status")).isEqualTo("ready");
        assertThat(out.get("narrative")).isEqualTo("All good.");
        assertThat(out.get("cached")).isEqualTo(true);
        verify(generator, never()).generate(any(), any(), any());
        verify(store, never()).markGenerating(any());
    }

    @Test
    void getReport_generatingAndFresh_returnsGenerating_withoutRedispatch() {
        var row = new ClassReportStore.StoredReport(CLASS, null, "generating",
                null, null, Instant.now());
        when(store.find(CLASS)).thenReturn(Optional.of(row));

        Map<String, Object> out = service.getReport(USER, ORG, CLASS);

        assertThat(out.get("status")).isEqualTo("generating");
        verify(generator, never()).generate(any(), any(), any());
        verify(store, never()).markGenerating(any());
    }

    @Test
    void getReport_failed_surfacesMessage_withoutAutoRetry() {
        var row = new ClassReportStore.StoredReport(CLASS, null, "failed",
                "boom", null, Instant.now());
        when(store.find(CLASS)).thenReturn(Optional.of(row));

        Map<String, Object> out = service.getReport(USER, ORG, CLASS);

        assertThat(out.get("status")).isEqualTo("failed");
        assertThat(out.get("message")).isEqualTo("boom");
        verify(generator, never()).generate(any(), any(), any());
    }

    @Test
    void getReport_staleReady_redispatches() {
        var row = new ClassReportStore.StoredReport(CLASS, "old", "ready",
                null, Instant.now().minus(Duration.ofHours(2)), Instant.now());
        when(store.find(CLASS)).thenReturn(Optional.of(row));

        Map<String, Object> out = service.getReport(USER, ORG, CLASS);

        assertThat(out.get("status")).isEqualTo("generating");
        verify(store).markGenerating(CLASS);
        verify(generator).generate(USER, ORG, CLASS);
    }

    @Test
    void getReport_forceRefresh_evictsAndRegenerates() {
        Map<String, Object> out = service.getReport(USER, ORG, CLASS, true);

        assertThat(out.get("status")).isEqualTo("generating");
        verify(store).delete(CLASS);
        verify(store, never()).find(any()); // refresh skips the read
        verify(generator).generate(USER, ORG, CLASS);
    }

    @Test
    void getReport_poolRejected_returnsGenerating_neverThrows() {
        when(store.find(CLASS)).thenReturn(Optional.empty());
        doThrow(new RejectedExecutionException("pool full"))
                .when(generator).generate(USER, ORG, CLASS);

        Map<String, Object> out = service.getReport(USER, ORG, CLASS);

        // Row stays 'generating'; client re-polls — must not 500.
        assertThat(out.get("status")).isEqualTo("generating");
    }

    @Test
    void getReport_checksStaffAuthFirst_andStopsWhenDenied() {
        doThrow(new RuntimeException("not staff"))
                .when(centreAccessService).ensureStaff(USER, ORG);

        try {
            service.getReport(USER, ORG, CLASS);
        } catch (RuntimeException ignored) {
            // expected
        }
        verify(centreAccessService).ensureStaff(USER, ORG);
        verify(store, never()).find(anyString());
        verify(generator, never()).generate(any(), any(), any());
    }

    @Test
    void evictCache_deletesPersistedRow() {
        service.evictCache(CLASS);
        verify(store).delete(eq(CLASS));
    }
}
