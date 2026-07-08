package com.pally.domain.cost;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiUsageMeterTest {

    @Mock AiUsageRepository repository;
    private final AiCostRates rates = new AiCostRates(); // baked default rates

    private AiUsageMeter meter() { return new AiUsageMeter(repository, rates); }

    @Test
    void record_full_persistsAllAttributionFields() {
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        meter().record("u1", "av1", AiCallType.COMPILE, "module-learn",
                AiTrigger.PAGE_UPDATE, "gemini-2.5-flash", 1000, 500, true, true);

        ArgumentCaptor<AiUsage> cap = ArgumentCaptor.forClass(AiUsage.class);
        verify(repository).save(cap.capture());
        AiUsage u = cap.getValue();
        assertThat(u.avatarId()).isEqualTo("av1");
        assertThat(u.purposeLabel()).isEqualTo("module-learn");
        assertThat(u.trigger()).isEqualTo(AiTrigger.PAGE_UPDATE);
        assertThat(u.success()).isTrue();
        assertThat(u.estimated()).isTrue();      // char-estimate flagged
    }

    @Test
    void fromLabel_mapsFineLabelToCoarseCategory() {
        assertThat(AiCallType.fromLabel("teach-eval")).isEqualTo(AiCallType.COMPILE);
        assertThat(AiCallType.fromLabel("module-learn")).isEqualTo(AiCallType.COMPILE);
        assertThat(AiCallType.fromLabel("relevance-check")).isEqualTo(AiCallType.RELEVANCE);
        assertThat(AiCallType.fromLabel("topic-router")).isEqualTo(AiCallType.OTHER);
    }

    @Test
    void record_computesEstCostFromRates_andSavesTheRow() {
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        // gemini-2.5-flash: input $0.30/M, output $2.50/M (real GA rate).
        meter().record("u1", AiCallType.COMPILE, "gemini-2.5-flash", 1_000_000, 100_000);

        ArgumentCaptor<AiUsage> cap = ArgumentCaptor.forClass(AiUsage.class);
        verify(repository).save(cap.capture());
        AiUsage u = cap.getValue();
        assertThat(u.userId()).isEqualTo("u1");
        assertThat(u.callType()).isEqualTo(AiCallType.COMPILE);
        assertThat(u.inputTokens()).isEqualTo(1_000_000);
        // 1e6*0.30 + 1e5*2.50 = 300000 + 250000 = 550000 micros ($0.55).
        assertThat(u.estCostMicros()).isEqualTo(550_000);
    }

    @Test
    void record_nullUserId_isRecordedNotDropped() {
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        meter().record(null, AiCallType.CHAT, "claude-haiku-4-5-20251001", 500, 500);

        ArgumentCaptor<AiUsage> cap = ArgumentCaptor.forClass(AiUsage.class);
        verify(repository).save(cap.capture());
        assertThat(cap.getValue().userId()).isNull();
    }

    @Test
    void record_isBestEffort_neverThrows_soAiCallsAreNeverBroken() {
        doThrow(new RuntimeException("db down")).when(repository).save(any());
        assertThatCode(() ->
                meter().record("u1", AiCallType.COMPILE, "gemini-2.5-flash", 10, 10))
                .doesNotThrowAnyException();
    }

    @Test
    void record_unknownModel_savesWithZeroCost_notDropped() {
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        meter().record("u1", AiCallType.OTHER, "some-future-model", 1000, 1000);

        ArgumentCaptor<AiUsage> cap = ArgumentCaptor.forClass(AiUsage.class);
        verify(repository).save(cap.capture());
        assertThat(cap.getValue().estCostMicros()).isZero();
    }
}
