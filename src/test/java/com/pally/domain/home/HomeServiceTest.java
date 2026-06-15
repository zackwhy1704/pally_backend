package com.pally.domain.home;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.quiz.CardRating;
import com.pally.domain.quiz.FlashCard;
import com.pally.domain.quiz.FlashcardRepository;
import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HomeService}.
 * Verifies nudge generation logic across all branching conditions.
 */
@ExtendWith(MockitoExtension.class)
class HomeServiceTest {

    private static final String USER_ID   = "user-1";
    private static final String AVATAR_ID = "avatar-1";

    @Mock UserRepository userRepository;
    @Mock AvatarRepository avatarRepository;
    @Mock FlashcardRepository flashcardRepository;

    @InjectMocks HomeService service;

    // ── helpers ──────────────────────────────────────────────────────────────

    private User userWith(int streakDays, int xp, int level) {
        User u = new User();
        u.setId(USER_ID);
        u.setStreakDays(streakDays);
        u.setXp(xp);
        u.setLevel(level);
        return u;
    }

    private Avatar avatar(String id) {
        return Avatar.create(USER_ID, "Bot", Subject.MATHS, CharacterType.MOCHI);
        // Note: Avatar.create uses an internal ID — we expose the id from domain;
        // for mocking findDueByAvatarId we use AVATAR_ID via avatar.getId() in the chain.
        // A workaround: reconstitute an avatar with a fixed id via a test avatar stub.
    }

    private FlashCard dueCard() {
        return new FlashCard(
                "card-1", AVATAR_ID, "front", "back", "slug",
                CardRating.EASY, Instant.now().minusSeconds(3600),
                1, 2.5, 1);
    }

    // ── getNudges ────────────────────────────────────────────────────────────

    @Test
    void getNudges_streakAndDueCards_returnsBothNudges() {
        User user = userWith(3, 0, 1);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        Avatar av = Avatar.create(USER_ID, "Bot", Subject.MATHS, CharacterType.MOCHI);
        when(avatarRepository.findByUserId(USER_ID)).thenReturn(List.of(av));
        when(flashcardRepository.findDueByAvatarId(av.getId())).thenReturn(List.of(dueCard()));

        Map<String, Object> result = service.getNudges(USER_ID);

        verify(userRepository).ensureUserExists(USER_ID);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> nudges = (List<Map<String, String>>) result.get("nudges");
        assertThat(nudges).hasSize(2);
        assertThat(nudges.get(0).get("type")).isEqualTo("streak");
        assertThat(nudges.get(1).get("type")).isEqualTo("flashcard");
    }

    @Test
    void getNudges_noStreakNoDue_returnsFallbackNudge() {
        User user = userWith(0, 0, 1);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(avatarRepository.findByUserId(USER_ID)).thenReturn(List.of());

        Map<String, Object> result = service.getNudges(USER_ID);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> nudges = (List<Map<String, String>>) result.get("nudges");
        assertThat(nudges).hasSize(1);
        assertThat(nudges.get(0).get("type")).isEqualTo("content");
    }

    @Test
    void getNudges_xpNearMilestone_returnsQuizNudge() {
        // xp=110 → 110 % 100 = 10 < 20 → milestone nudge
        User user = userWith(0, 110, 2);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(avatarRepository.findByUserId(USER_ID)).thenReturn(List.of());

        Map<String, Object> result = service.getNudges(USER_ID);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> nudges = (List<Map<String, String>>) result.get("nudges");
        assertThat(nudges).hasSize(1);
        assertThat(nudges.get(0).get("type")).isEqualTo("quiz");
        assertThat(nudges.get(0).get("message")).contains("level 3");
    }

    @Test
    void getNudges_nullUser_returnsFallbackWithoutException() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(avatarRepository.findByUserId(USER_ID)).thenReturn(List.of());

        Map<String, Object> result = service.getNudges(USER_ID);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> nudges = (List<Map<String, String>>) result.get("nudges");
        assertThat(nudges).hasSize(1);
        assertThat(nudges.get(0).get("type")).isEqualTo("content");
    }

    @Test
    void getNudges_singleDueCard_usesCorrectSingularLabel() {
        User user = userWith(0, 0, 1);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        Avatar av = Avatar.create(USER_ID, "Bot", Subject.MATHS, CharacterType.MOCHI);
        when(avatarRepository.findByUserId(USER_ID)).thenReturn(List.of(av));
        when(flashcardRepository.findDueByAvatarId(av.getId())).thenReturn(List.of(dueCard()));

        Map<String, Object> result = service.getNudges(USER_ID);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> nudges = (List<Map<String, String>>) result.get("nudges");
        Map<String, String> fcNudge = nudges.stream()
                .filter(n -> "flashcard".equals(n.get("type")))
                .findFirst().orElseThrow();
        assertThat(fcNudge.get("message")).startsWith("1 flashcard due");
    }
}
