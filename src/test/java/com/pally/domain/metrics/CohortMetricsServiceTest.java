package com.pally.domain.metrics;

import com.pally.domain.metrics.dto.CohortMetrics;
import com.pally.domain.metrics.CohortMetricsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Pins the two ways these numbers could silently mislead in a sales conversation.
 *
 * <p><b>1. A misleading zero.</b> flashcard_review is empty in production today — no
 * card has ever been reviewed twice. A verified-retention rate of "0%" would assert
 * that students retain NOTHING; the truth is that the behaviour has not happened yet.
 * Those are opposite claims and only one is supported by the data.
 *
 * <p><b>2. A poisoned denominator.</b> 61 of 79 production avatars hold content but
 * have zero activity — cards auto-generated at compile time for accounts that never
 * engaged. Counting them understates every rate; hiding them overstates the
 * population. Both numbers must ship.
 */
@ExtendWith(MockitoExtension.class)
class CohortMetricsServiceTest {

    @Mock CohortMetricsRepository repo;
    private CohortMetricsService service;

    @BeforeEach
    void setUp() {
        service = new CohortMetricsService(repo);
        // Default: an empty world. Individual tests override what they exercise.
        lenient().when(repo.countAllAvatars(any(), any())).thenReturn(0);
        lenient().when(repo.countActiveAvatars(any(), any())).thenReturn(0);
        lenient().when(repo.countDormantAvatars(any(), any())).thenReturn(0);
        lenient().when(repo.countReturnedOnLaterDay(any(), any())).thenReturn(0);
        lenient().when(repo.countReviewedCards(any(), any())).thenReturn(0);
        lenient().when(repo.countRepeatReviewedCards(any(), any())).thenReturn(0);
        lenient().when(repo.countRepeatRecallAttempts(any(), any())).thenReturn(0);
        lenient().when(repo.countRepeatRecallCorrect(any(), any())).thenReturn(0);
        lenient().when(repo.countAvatarsWithSecondDay(any(), any())).thenReturn(0);
        lenient().when(repo.medianDaysToSecondSession(any(), any())).thenReturn(null);
    }

    // ── THE CRITICAL CASE: empty denominator must NEVER read as 0% ───────────

    @Test
    void verifiedRetention_withNoRepeatReviews_isNullAndMarked_NOT_zero() {
        // Production reality today: flashcard_review has 0 rows.
        CohortMetrics m = service.compute(null, null);

        assertThat(m.verifiedRetention().value())
                .as("0%% would claim students retain NOTHING; the truth is it hasn't happened yet")
                .isNull();
        // Null-safe: the point is that it is not the number 0.0, and AssertJ's
        // isNotEqualTo(double) would itself NPE on a null actual.
        assertThat(Double.valueOf(0.0).equals(m.verifiedRetention().value())).isFalse();
        assertThat(m.verifiedRetention().status())
                .isEqualTo(CohortMetrics.Status.INSUFFICIENT_DATA);
        assertThat(m.verifiedRetention().denominator())
                .as("denominator must be visible so a reader sees WHY it is null")
                .isZero();
    }

    @Test
    void repeatReview_withNoReviewedCards_isNullAndMarked_NOT_zero() {
        CohortMetrics m = service.compute(null, null);

        assertThat(m.repeatReview().value()).isNull();
        assertThat(Double.valueOf(0.0).equals(m.repeatReview().value())).isFalse();
        assertThat(m.repeatReview().status()).isEqualTo(CohortMetrics.Status.INSUFFICIENT_DATA);
    }

    @Test
    void aGenuineZero_isStillReportedAsZero_notSuppressed() {
        // The inverse failure: if the denominator is REAL and the numerator is 0,
        // that is a true 0% and must NOT be hidden behind INSUFFICIENT_DATA.
        when(repo.countReviewedCards(any(), any())).thenReturn(40);
        when(repo.countRepeatReviewedCards(any(), any())).thenReturn(0);

        CohortMetrics m = service.compute(null, null);

        assertThat(m.repeatReview().value()).isEqualTo(0.0);
        assertThat(m.repeatReview().status()).isEqualTo(CohortMetrics.Status.OK);
        assertThat(m.repeatReview().denominator()).isEqualTo(40);
    }

    @Test
    void noRateEverReturnsNaN() {
        CohortMetrics m = service.compute(null, null);

        for (CohortMetrics.Rate r : new CohortMetrics.Rate[]{
                m.activation(), m.returnRate(), m.repeatReview(), m.verifiedRetention()}) {
            assertThat(r.value() == null || !r.value().isNaN())
                    .as("a NaN would render as garbage in any dashboard").isTrue();
        }
    }

    // ── DORMANT AVATARS: reported, never in a denominator ────────────────────

    @Test
    void dormantAvatars_areReported_butExcludedFromTheActivityDenominator() {
        // Mirrors production: 79 avatars hold content, only 18 ever acted.
        when(repo.countAllAvatars(any(), any())).thenReturn(79);
        when(repo.countActiveAvatars(any(), any())).thenReturn(18);
        when(repo.countDormantAvatars(any(), any())).thenReturn(61);
        when(repo.countReturnedOnLaterDay(any(), any())).thenReturn(9);

        CohortMetrics m = service.compute(null, null);

        assertThat(m.dormantAvatars()).as("never silently dropped").isEqualTo(61);
        assertThat(m.activeAvatars()).isEqualTo(18);
        // Return rate is over ACTIVE avatars — a dormant avatar never arrived, so it
        // cannot "fail to return". 9/18 = 50%, NOT 9/79 = 11.4%.
        assertThat(m.returnRate().denominator()).isEqualTo(18);
        assertThat(m.returnRate().value()).isEqualTo(50.0, within(1e-9));
    }

    @Test
    void activation_isOverALLAvatars_sinceThatIsWhatActivationMeans() {
        when(repo.countAllAvatars(any(), any())).thenReturn(79);
        when(repo.countActiveAvatars(any(), any())).thenReturn(18);

        CohortMetrics m = service.compute(null, null);

        assertThat(m.activation().denominator()).isEqualTo(79);
        assertThat(m.activation().value()).isEqualTo(22.78, within(0.01));
    }

    // ── LOW-N MEDIAN: reported with its caveat, not hidden ───────────────────

    @Test
    void medianDays_withRealButTinyN_isReportedAndFlaggedLowN() {
        when(repo.medianDaysToSecondSession(any(), any())).thenReturn(3.0);
        when(repo.countAvatarsWithSecondDay(any(), any())).thenReturn(2);

        CohortMetrics m = service.compute(null, null);

        assertThat(m.medianDaysFirstToSecond().days()).isEqualTo(3.0);
        assertThat(m.medianDaysFirstToSecond().n()).isEqualTo(2);
        assertThat(m.medianDaysFirstToSecond().lowN())
                .as("n=2 is a real number with a real caveat — flag, don't hide").isTrue();
        assertThat(m.medianDaysFirstToSecond().status()).isEqualTo(CohortMetrics.Status.OK);
    }

    @Test
    void medianDays_withNobodyReturning_isNullNotZeroDays() {
        CohortMetrics m = service.compute(null, null);

        assertThat(m.medianDaysFirstToSecond().days())
                .as("0 days would mean 'returned same day', the opposite of never")
                .isNull();
        assertThat(m.medianDaysFirstToSecond().status())
                .isEqualTo(CohortMetrics.Status.INSUFFICIENT_DATA);
    }

    @Test
    void medianDays_withHealthyN_isNotFlaggedLowN() {
        when(repo.medianDaysToSecondSession(any(), any())).thenReturn(4.5);
        when(repo.countAvatarsWithSecondDay(any(), any())).thenReturn(40);

        CohortMetrics m = service.compute(null, null);

        assertThat(m.medianDaysFirstToSecond().lowN()).isFalse();
    }

    // ── FILTERS ──────────────────────────────────────────────────────────────

    @Test
    void blankFiltersAreNormalisedToNull_soTheyMeanAllAvatars() {
        CohortMetrics m = service.compute("  ", "");

        assertThat(m.levelFilter()).isNull();
        assertThat(m.subjectFilter()).isNull();
    }

    @Test
    void filtersAreEchoedBack_soAReaderKnowsWhatTheNumbersCover() {
        CohortMetrics m = service.compute("SECONDARY", "MATHS");

        assertThat(m.levelFilter()).isEqualTo("SECONDARY");
        assertThat(m.subjectFilter()).isEqualTo("MATHS");
    }
}
