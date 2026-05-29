package com.pally.domain.progress;

import com.pally.domain.avatar.Subject;
import com.pally.infrastructure.persistence.activity.ActivityLogJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// Locks the four farm-stop behaviours: decay shape, variety bonus,
/// chat dedup, and that the credited amount (not the requested) is what
/// flows to addXpAndStars. These rules are what make Pally's economy
/// behave like Duolingo's (reduce repeat-XP) instead of letting kids
/// grind to L30 in an afternoon.
@ExtendWith(MockitoExtension.class)
class XpServiceTest {

    @Mock UserRepository userRepository;
    @Mock ActivityLogJpaRepository activityLog;
    @InjectMocks XpService xp;

    private static final String USER = "u1";
    private static final String AVATAR = "a1";
    private static final Subject SUBJ = Subject.MATHS;

    private UserRepository.XpResult okCredit(int xp) {
        return new UserRepository.XpResult(xp, 1, 1, false, null);
    }

    /// First quiz of a NEW subject today: 100% × 1.5 (variety) = 150%.
    @Test
    void awardForQuiz_firstOfDay_andNewSubject_appliesVariety() {
        when(activityLog.countByTypeAndAvatarBetween(
                eq(USER), eq("QUIZ"), eq(AVATAR), any(), any()))
                .thenReturn(0);
        when(activityLog.countByTypeAndSubjectBetween(
                eq(USER), eq("QUIZ"), eq("MATHS"), any(), any()))
                .thenReturn(0);
        when(userRepository.addXpAndStars(eq(USER), eq(30), eq(15)))
                .thenReturn(okCredit(30));

        var award = xp.awardForQuiz(USER, AVATAR, SUBJ, 20);

        assertThat(award.xpGranted()).isEqualTo(30); // 20 * 1.5
        assertThat(award.starsGranted()).isEqualTo(15); // 30 * 0.5
        assertThat(award.varietyBonus()).isTrue();
        assertThat(award.decayStep()).isZero();
        assertThat(award.multiplier()).isEqualTo(1.5);
    }

    /// First quiz of the day on an avatar whose subject was ALREADY
    /// quizzed today — no variety bonus, just full base.
    @Test
    void awardForQuiz_firstOfDay_existingSubject_noVariety() {
        when(activityLog.countByTypeAndAvatarBetween(
                eq(USER), eq("QUIZ"), eq(AVATAR), any(), any()))
                .thenReturn(0);
        when(activityLog.countByTypeAndSubjectBetween(
                eq(USER), eq("QUIZ"), eq("MATHS"), any(), any()))
                .thenReturn(1);
        when(userRepository.addXpAndStars(eq(USER), eq(20), eq(10)))
                .thenReturn(okCredit(20));

        var award = xp.awardForQuiz(USER, AVATAR, SUBJ, 20);

        assertThat(award.xpGranted()).isEqualTo(20);
        assertThat(award.varietyBonus()).isFalse();
        assertThat(award.multiplier()).isEqualTo(1.0);
    }

    /// 2nd quiz on the same avatar today → 50% decay (no variety since
    /// the subject is obviously already quizzed).
    @Test
    void awardForQuiz_secondOnAvatar_decaysToHalf() {
        when(activityLog.countByTypeAndAvatarBetween(
                eq(USER), eq("QUIZ"), eq(AVATAR), any(), any()))
                .thenReturn(1);
        when(activityLog.countByTypeAndSubjectBetween(
                eq(USER), eq("QUIZ"), eq("MATHS"), any(), any()))
                .thenReturn(1);
        when(userRepository.addXpAndStars(eq(USER), eq(10), eq(5)))
                .thenReturn(okCredit(10));

        var award = xp.awardForQuiz(USER, AVATAR, SUBJ, 20);

        assertThat(award.xpGranted()).isEqualTo(10);
        assertThat(award.multiplier()).isEqualTo(0.5);
        assertThat(award.decayStep()).isEqualTo(1);
    }

    /// 3rd → 25%, 4th → 10%, 5th-and-beyond also 10% (floor, never zero).
    @Test
    void awardForQuiz_decayCurveBeyondFloor() {
        // 3rd quiz
        when(activityLog.countByTypeAndAvatarBetween(
                eq(USER), eq("QUIZ"), eq(AVATAR), any(), any()))
                .thenReturn(2);
        when(activityLog.countByTypeAndSubjectBetween(
                eq(USER), eq("QUIZ"), eq("MATHS"), any(), any()))
                .thenReturn(1);
        when(userRepository.addXpAndStars(eq(USER), anyInt(), anyInt()))
                .thenReturn(okCredit(0));

        var third = xp.awardForQuiz(USER, AVATAR, SUBJ, 20);
        assertThat(third.multiplier()).isEqualTo(0.25);

        when(activityLog.countByTypeAndAvatarBetween(
                eq(USER), eq("QUIZ"), eq(AVATAR), any(), any()))
                .thenReturn(3);
        var fourth = xp.awardForQuiz(USER, AVATAR, SUBJ, 20);
        assertThat(fourth.multiplier()).isEqualTo(0.10);

        when(activityLog.countByTypeAndAvatarBetween(
                eq(USER), eq("QUIZ"), eq(AVATAR), any(), any()))
                .thenReturn(10);
        var tenth = xp.awardForQuiz(USER, AVATAR, SUBJ, 20);
        assertThat(tenth.multiplier())
                .as("floor never drops below 10%").isEqualTo(0.10);
    }

    /// Chat session-end: first call of the day credits; second is dedup'd.
    @Test
    void awardForChat_firstOfDay_credits() {
        when(activityLog.countByTypeAndAvatarBetween(
                eq(USER), eq("CHAT"), eq(AVATAR), any(), any()))
                .thenReturn(0);
        when(userRepository.addXpAndStars(eq(USER), eq(5), eq(2)))
                .thenReturn(okCredit(5));

        var award = xp.awardForChat(USER, AVATAR);

        assertThat(award.xpGranted()).isEqualTo(5);
        assertThat(award.starsGranted()).isEqualTo(2);
        assertThat(award.alreadyCreditedToday()).isFalse();
    }

    @Test
    void awardForChat_secondOfDay_isDeduped() {
        when(activityLog.countByTypeAndAvatarBetween(
                eq(USER), eq("CHAT"), eq(AVATAR), any(), any()))
                .thenReturn(1);
        when(userRepository.findById(USER))
                .thenReturn(Optional.of(new UserStats(USER, null, 50, 1, 0, 10)));

        var award = xp.awardForChat(USER, AVATAR);

        assertThat(award.xpGranted()).isZero();
        assertThat(award.alreadyCreditedToday()).isTrue();
        // Importantly: addXpAndStars must NOT be called when we dedup.
        verify(userRepository, never()).addXpAndStars(anyString(), anyInt(), anyInt());
    }

    /// Sanity: a null subject (degenerate avatar) just skips variety.
    @Test
    void awardForQuiz_nullSubject_skipsVariety() {
        when(activityLog.countByTypeAndAvatarBetween(
                anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(0);
        when(userRepository.addXpAndStars(eq(USER), eq(20), eq(10)))
                .thenReturn(okCredit(20));

        var award = xp.awardForQuiz(USER, AVATAR, null, 20);

        assertThat(award.varietyBonus()).isFalse();
        assertThat(award.xpGranted()).isEqualTo(20);
    }

    /// Granted amount, not requested, is what gets persisted. The decay
    /// rule has zero effect if the post-multiplier value isn't the one
    /// handed to the repository.
    @Test
    void awardForQuiz_persistsGrantedAmount_notRaw() {
        when(activityLog.countByTypeAndAvatarBetween(
                eq(USER), eq("QUIZ"), eq(AVATAR), any(), any()))
                .thenReturn(1); // 50% decay
        when(activityLog.countByTypeAndSubjectBetween(
                anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(1);
        when(userRepository.addXpAndStars(eq(USER), anyInt(), anyInt()))
                .thenReturn(okCredit(0));

        xp.awardForQuiz(USER, AVATAR, SUBJ, 40); // 40 * 0.5 = 20

        ArgumentCaptor<Integer> xpCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> starsCap = ArgumentCaptor.forClass(Integer.class);
        verify(userRepository, times(1)).addXpAndStars(
                eq(USER), xpCap.capture(), starsCap.capture());
        assertThat(xpCap.getValue()).isEqualTo(20);
        assertThat(starsCap.getValue()).isEqualTo(10); // 20 * 0.5
    }
}
