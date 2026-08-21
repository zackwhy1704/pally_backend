package com.pally.domain.quiz.usecase;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.quiz.CardRating;
import com.pally.domain.quiz.FlashCard;
import com.pally.domain.quiz.FlashcardRepository;
import com.pally.domain.quiz.FlashcardReview;
import com.pally.domain.quiz.FlashcardReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the flashcard review-history instrumentation (V131).
 *
 * <p>The defect it addresses: {@code flashcardRepository.save} overwrites the card's
 * SM-2 state on the same primary key, so every prior recall outcome is permanently
 * destroyed. Production consequence — 2,106 cards with {@code MAX(repetitions)=1}
 * and no way to tell a never-reviewed card from one reviewed and reset by a HARD
 * rating, because HARD (q=2 &lt; 3) sets {@code repetitions=0, intervalDays=1}.
 *
 * <p>Instrumentation ONLY: nothing here computes a retention rate. No card in
 * production has ever been successfully reviewed twice, so such a metric would be
 * structurally zero.
 */
@ExtendWith(MockitoExtension.class)
class RateFlashcardReviewHistoryTest {

    private static final String CARD_ID = "card-1";
    private static final String AVATAR_ID = "avatar-1";
    private static final String OWNER = "user-1";

    @Mock FlashcardRepository flashcardRepository;
    @Mock AvatarRepository avatarRepository;
    @Mock FlashcardReviewRepository flashcardReviewRepository;

    private RateFlashcardUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new RateFlashcardUseCase(
                flashcardRepository, avatarRepository, flashcardReviewRepository);
        lenient().when(avatarRepository.existsByIdAndUserId(anyString(), anyString()))
                .thenReturn(true);
        // The real repository persists and returns; mirror that so the use case sees
        // the post-SM-2 card as "saved".
        lenient().when(flashcardRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    /** A brand-new, never-reviewed card: reps 0, interval 1, no scheduled review. */
    private FlashCard freshCard() {
        return new FlashCard(CARD_ID, AVATAR_ID, "front", "back", "slug",
                null, null, 0, 2.5, 1);
    }

    private FlashcardReview captureReview() {
        ArgumentCaptor<FlashcardReview> captor = ArgumentCaptor.forClass(FlashcardReview.class);
        verify(flashcardReviewRepository).save(captor.capture());
        return captor.getValue();
    }

    // ── The core instrumentation ─────────────────────────────────────────────

    @Test
    void everyRating_writesOneHistoryRow_capturingBeforeAndAfterState() {
        when(flashcardRepository.findById(CARD_ID)).thenReturn(Optional.of(freshCard()));

        useCase.execute(CARD_ID, CardRating.EASY, OWNER);

        FlashcardReview r = captureReview();
        assertThat(r.flashcardId()).isEqualTo(CARD_ID);
        assertThat(r.avatarId()).isEqualTo(AVATAR_ID);
        assertThat(r.rating()).isEqualTo(CardRating.EASY);
        assertThat(r.quality()).as("EASY maps to SM-2 q=5").isEqualTo(5);

        // BEFORE: the state the destructive save is about to obliterate.
        assertThat(r.prevRepetitions()).isZero();
        assertThat(r.prevIntervalDays()).isEqualTo(1);
        assertThat(r.prevNextReviewAt()).as("first ever review has no prior schedule").isNull();

        // AFTER: what the flashcards row now holds.
        assertThat(r.newRepetitions()).isEqualTo(1);
        assertThat(r.newNextReviewAt()).isNotNull();
    }

    @Test
    void aGenuineRepeatSequence_isFullyReconstructibleFromTheTableAlone() {
        // HARD -> OKAY -> OKAY on ONE card. This is THE question the table exists to
        // answer: which of these was a genuine repeat recall rather than a restart?
        List<FlashcardReview> history = new ArrayList<>();
        when(flashcardReviewRepository.save(any())).thenAnswer(i -> {
            history.add(i.getArgument(0));
            return i.getArgument(0);
        });

        FlashCard current = freshCard();
        for (CardRating rating : List.of(CardRating.HARD, CardRating.OKAY, CardRating.OKAY)) {
            when(flashcardRepository.findById(CARD_ID)).thenReturn(Optional.of(current));
            current = useCase.execute(CARD_ID, rating, OWNER);
        }

        assertThat(history).hasSize(3);

        // Review 1 — HARD on a fresh card. q=2 is a LAPSE: reps reset to 0.
        FlashcardReview first = history.get(0);
        assertThat(first.quality()).isEqualTo(2);
        assertThat(first.prevRepetitions()).isZero();
        assertThat(first.newRepetitions()).as("HARD resets, it does not advance").isZero();
        assertThat(first.isRepeatRecall()).isFalse();

        // Review 2 — OKAY, but the card was sitting at reps=0 after the HARD reset.
        // Still NOT a repeat recall: this is a restart, and the flashcards row alone
        // could never have told us that.
        FlashcardReview second = history.get(1);
        assertThat(second.prevRepetitions()).isZero();
        assertThat(second.newRepetitions()).isEqualTo(1);
        assertThat(second.isRepeatRecall())
                .as("a review following a HARD reset is a restart, not a repeat")
                .isFalse();

        // Review 3 — OKAY on a card already at reps=1. THIS is a genuine repeat
        // recall, and the interval finally reaches the 6-day SM-2 step.
        FlashcardReview third = history.get(2);
        assertThat(third.prevRepetitions()).isEqualTo(1);
        assertThat(third.newRepetitions()).isEqualTo(2);
        assertThat(third.newIntervalDays()).as("SM-2 second-success step is 6 days").isEqualTo(6);
        assertThat(third.isRepeatRecall()).isTrue();

        // Exactly one genuine repeat in this sequence — the number that was
        // unrecoverable before this table existed.
        assertThat(history.stream().filter(FlashcardReview::isRepeatRecall).count()).isEqualTo(1);
    }

    @Test
    void historyChainsCorrectly_eachReviewsPrevMatchesThePriorReviewsNew() {
        // Guards against recording a stale or recomputed "before" state: the table
        // must be a continuous chain, or reconstruction silently lies.
        List<FlashcardReview> history = new ArrayList<>();
        when(flashcardReviewRepository.save(any())).thenAnswer(i -> {
            history.add(i.getArgument(0));
            return i.getArgument(0);
        });

        FlashCard current = freshCard();
        for (CardRating rating : List.of(CardRating.OKAY, CardRating.EASY, CardRating.OKAY)) {
            when(flashcardRepository.findById(CARD_ID)).thenReturn(Optional.of(current));
            current = useCase.execute(CARD_ID, rating, OWNER);
        }

        for (int i = 1; i < history.size(); i++) {
            FlashcardReview prior = history.get(i - 1);
            FlashcardReview next = history.get(i);
            assertThat(next.prevRepetitions()).isEqualTo(prior.newRepetitions());
            assertThat(next.prevIntervalDays()).isEqualTo(prior.newIntervalDays());
            assertThat(next.prevEaseFactor()).isEqualTo(prior.newEaseFactor(), within(1e-9));
            assertThat(next.prevNextReviewAt()).isEqualTo(prior.newNextReviewAt());
        }
    }

    @Test
    void recordedQuality_matchesTheQTheSchedulerActuallyActedOn() {
        // One mapping, not two: a divergent copy would make history a record of
        // something that never happened.
        for (CardRating rating : CardRating.values()) {
            when(flashcardRepository.findById(CARD_ID)).thenReturn(Optional.of(freshCard()));
            ArgumentCaptor<FlashcardReview> captor = ArgumentCaptor.forClass(FlashcardReview.class);

            useCase.execute(CARD_ID, rating, OWNER);

            verify(flashcardReviewRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
            assertThat(captor.getValue().quality())
                    .isEqualTo(com.pally.domain.quiz.Sm2Scheduler.qualityOf(rating));
        }
    }

    // ── Best-effort: instrumentation must never cost a student their rating ──

    @Test
    void historyInsertFailure_neverBreaksTheRating() {
        when(flashcardRepository.findById(CARD_ID)).thenReturn(Optional.of(freshCard()));
        doThrow(new RuntimeException("history table unavailable"))
                .when(flashcardReviewRepository).save(any());

        assertThatCode(() -> useCase.execute(CARD_ID, CardRating.EASY, OWNER))
                .as("a metrics table outage must not lose the student's review")
                .doesNotThrowAnyException();

        // The SM-2 update still happened and still persisted.
        verify(flashcardRepository).save(any());
    }

    @Test
    void sm2SchedulingBehaviour_isUnchangedByTheInstrumentation() {
        // Additive-only guarantee: the card returned to the caller is exactly what
        // Sm2Scheduler produces, untouched by history recording.
        FlashCard before = freshCard();
        when(flashcardRepository.findById(CARD_ID)).thenReturn(Optional.of(before));

        FlashCard actual = useCase.execute(CARD_ID, CardRating.OKAY, OWNER);
        FlashCard expected = com.pally.domain.quiz.Sm2Scheduler.applyRating(before, CardRating.OKAY);

        assertThat(actual.repetitions()).isEqualTo(expected.repetitions());
        assertThat(actual.intervalDays()).isEqualTo(expected.intervalDays());
        assertThat(actual.easeFactor()).isEqualTo(expected.easeFactor(), within(1e-9));
        assertThat(actual.lastRating()).isEqualTo(expected.lastRating());
    }

    @Test
    void reviewedAtIsRecorded_soHistoryIsOrderable() {
        when(flashcardRepository.findById(CARD_ID)).thenReturn(Optional.of(freshCard()));
        Instant before = Instant.now();

        useCase.execute(CARD_ID, CardRating.OKAY, OWNER);

        assertThat(captureReview().reviewedAt()).isBetween(before, Instant.now());
    }
}
