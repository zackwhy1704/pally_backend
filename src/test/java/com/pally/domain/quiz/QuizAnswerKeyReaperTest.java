package com.pally.domain.quiz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuizAnswerKeyReaperTest {

    @Mock QuizAnswerKeyRepository answerKeyRepository;
    @InjectMocks QuizAnswerKeyReaper reaper;

    @Test
    void reap_deletesKeysOlderThanTheRetentionWindow() {
        ReflectionTestUtils.setField(reaper, "retentionDays", 7);
        when(answerKeyRepository.deleteOlderThan(org.mockito.ArgumentMatchers.any()))
                .thenReturn(42);

        Instant before = Instant.now().minus(7, ChronoUnit.DAYS);
        reaper.reap();
        Instant after = Instant.now().minus(7, ChronoUnit.DAYS);

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(answerKeyRepository).deleteOlderThan(cutoff.capture());
        // Cutoff is ~retentionDays ago (between the two reference points).
        assertThat(cutoff.getValue()).isBetween(before, after);
    }

    @Test
    void reap_swallowsAndLogs_aDeleteFailure_doesNotPropagate() {
        ReflectionTestUtils.setField(reaper, "retentionDays", 7);
        doThrow(new RuntimeException("db down"))
                .when(answerKeyRepository).deleteOlderThan(org.mockito.ArgumentMatchers.any());

        // A maintenance-job failure must never crash the scheduler thread.
        reaper.reap();

        verify(answerKeyRepository).deleteOlderThan(org.mockito.ArgumentMatchers.any());
    }
}
