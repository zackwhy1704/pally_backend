package com.pally.domain.challenge;

import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChallengeService}. Now targets the domain PORTS
 * ({@link ChallengeRepository}, {@link ChallengeAnswerRepository},
 * {@link ChallengeNotifier}) rather than JPA repos / FCM directly — same
 * invariants (locked answers, reveal gating, no user-ids in distribution,
 * reveal sweep notifies only answerers).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChallengeServiceTest {

    @Mock ChallengeRepository challengeRepo;
    @Mock ChallengeAnswerRepository answerRepo;
    @Mock ChallengeNotifier challengeNotifier;

    @InjectMocks ChallengeService service;

    private static final String CID = "challenge-1";

    private Challenge challenge(Instant revealAt) {
        return new Challenge(CID, "class-1", "2+2?", "[\"3\",\"4\",\"5\"]",
                "4", revealAt, "teacher-1", Instant.now(), false);
    }

    @Test
    void answer_locked_rejectsSecondAnswerWith409() {
        when(challengeRepo.findById(CID))
                .thenReturn(Optional.of(challenge(Instant.now().plus(1, ChronoUnit.HOURS))));
        when(answerRepo.existsByChallengeIdAndUserId(CID, "u1")).thenReturn(true);

        assertThatThrownBy(() -> service.answer(CID, "u1", "4"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 409);
        verify(answerRepo, never()).save(any());
    }

    @Test
    void answer_beforeReveal_doesNotReturnCorrectness() {
        when(challengeRepo.findById(CID))
                .thenReturn(Optional.of(challenge(Instant.now().plus(1, ChronoUnit.HOURS))));
        when(answerRepo.existsByChallengeIdAndUserId(CID, "u1")).thenReturn(false);

        Map<String, Object> resp = service.answer(CID, "u1", "4");

        assertThat(resp).containsEntry("answered", true);
        assertThat(resp).doesNotContainKey("correct"); // withheld pre-reveal
        verify(answerRepo).save(any());
    }

    @Test
    void get_beforeReveal_omitsAnswerAndDistribution() {
        when(challengeRepo.findById(CID))
                .thenReturn(Optional.of(challenge(Instant.now().plus(1, ChronoUnit.HOURS))));
        when(answerRepo.existsByChallengeIdAndUserId(CID, "u1")).thenReturn(true);

        Map<String, Object> m = service.get(CID, "u1");

        assertThat(m).containsEntry("revealed", false);
        assertThat(m).containsEntry("answered", true);
        assertThat(m).doesNotContainKey("answer");
        assertThat(m).doesNotContainKey("distribution");
        assertThat(m).doesNotContainKey("correct");
    }

    @Test
    void get_afterReveal_includesAnswerAndDistribution_withNoUserIds() {
        when(challengeRepo.findById(CID))
                .thenReturn(Optional.of(challenge(Instant.now().minus(1, ChronoUnit.MINUTES))));
        when(answerRepo.existsByChallengeIdAndUserId(CID, "u1")).thenReturn(true);
        when(answerRepo.findByChallengeIdAndUserId(CID, "u1"))
                .thenReturn(Optional.of(answerFor("u1", "4")));
        when(answerRepo.distributionByChallenge(CID)).thenReturn(List.of(
                new AnswerCount("4", 7L), new AnswerCount("3", 2L)));

        Map<String, Object> m = service.get(CID, "u1");

        assertThat(m).containsEntry("revealed", true);
        assertThat(m).containsEntry("answer", "4");
        assertThat(m).containsEntry("correct", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dist = (List<Map<String, Object>>) m.get("distribution");
        // MANDATORY: distribution rows expose only answer + count, no user ids.
        for (Map<String, Object> row : dist) {
            assertThat(row.keySet()).containsExactlyInAnyOrder("answer", "count");
            assertThat(row).doesNotContainKey("userId");
        }
        assertThat(dist.get(0)).containsEntry("answer", "4").containsEntry("count", 7L);
    }

    @Test
    void revealSweep_notifiesOnlyAnswerers_andMarksNotified() {
        Challenge due = challenge(Instant.now().minus(1, ChronoUnit.MINUTES));
        when(challengeRepo.findDueForReveal(any())).thenReturn(List.of(due));
        when(answerRepo.findByChallengeId(CID)).thenReturn(List.of(
                answerFor("u1", "4"), answerFor("u2", "3")));

        service.revealSweep();

        // Both answerers (and only answerers) are pushed to.
        verify(challengeNotifier, times(2)).sendToUser(anyString(), anyString(), anyString());
        verify(challengeNotifier).sendToUser(org.mockito.ArgumentMatchers.eq("u1"), anyString(), anyString());
        verify(challengeNotifier).sendToUser(org.mockito.ArgumentMatchers.eq("u2"), anyString(), anyString());
        assertThat(due.isNotified()).isTrue();
        verify(challengeRepo).save(due);
    }

    @Test
    void create_requiresQuestionAnswerReveal() {
        assertThatThrownBy(() -> service.create("class-1", "", null, "4",
                Instant.now().plus(1, ChronoUnit.HOURS), "teacher-1"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void delete_whenValidIdAndMatchingClass_deletesChallenge() {
        when(challengeRepo.findById(CID))
                .thenReturn(Optional.of(challenge(Instant.now().plus(1, ChronoUnit.HOURS))));

        service.delete(CID, "class-1");

        verify(challengeRepo).deleteById(CID);
    }

    @Test
    void delete_whenChallengeNotFound_throws404() {
        when(challengeRepo.findById(CID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(CID, "class-1"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 404);

        verify(challengeRepo, never()).deleteById(anyString());
    }

    @Test
    void delete_whenWrongClass_throws403() {
        when(challengeRepo.findById(CID))
                .thenReturn(Optional.of(challenge(Instant.now().plus(1, ChronoUnit.HOURS))));

        assertThatThrownBy(() -> service.delete(CID, "different-class"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 403);

        verify(challengeRepo, never()).deleteById(anyString());
    }

    private ChallengeAnswer answerFor(String userId, String ans) {
        return new ChallengeAnswer("a-" + userId, CID, userId, ans, Instant.now());
    }
}
