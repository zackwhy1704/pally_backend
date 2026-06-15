package com.pally.domain.home;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.quiz.FlashcardRepository;
import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Aggregates home-screen data for a given user.
 * Zero imports from {@code infrastructure.*} or {@code jakarta.persistence.*}.
 */
@Service
@RequiredArgsConstructor
public class HomeService {

    private final UserRepository userRepository;
    private final AvatarRepository avatarRepository;
    private final FlashcardRepository flashcardRepository;

    /**
     * Builds the list of nudges to display on the home screen.
     *
     * <ul>
     *   <li>Streak nudge — if the user has a live streak</li>
     *   <li>Flashcard nudge — total due cards across all user avatars</li>
     *   <li>XP milestone nudge — when the user is within 20 XP of the next 100-boundary</li>
     *   <li>Fallback "upload notes" nudge — when no other nudge applies</li>
     * </ul>
     *
     * @param userId the authenticated user id
     * @return a {@code Map} with a single {@code "nudges"} key
     */
    public Map<String, Object> getNudges(String userId) {
        userRepository.ensureUserExists(userId);
        User stats = userRepository.findById(userId).orElse(null);

        List<Map<String, String>> nudges = new ArrayList<>();

        // Streak nudge
        if (stats != null && stats.getStreakDays() > 0) {
            nudges.add(Map.of(
                    "type", "streak",
                    "emoji", "🔥",
                    "message", "You're on a " + stats.getStreakDays() + "-day streak! Don't break it."
            ));
        }

        // Due flashcards nudge — sum across all user avatars
        long dueCount = avatarRepository.findByUserId(userId).stream()
                .mapToLong(a -> flashcardRepository.findDueByAvatarId(a.getId()).size())
                .sum();

        if (dueCount > 0) {
            nudges.add(Map.of(
                    "type", "flashcard",
                    "emoji", "⚡",
                    "message", dueCount + " flashcard" + (dueCount == 1 ? "" : "s") + " due today!"
            ));
        }

        // XP milestone nudge
        if (stats != null && stats.getXp() > 0 && stats.getXp() % 100 < 20) {
            nudges.add(Map.of(
                    "type", "quiz",
                    "emoji", "🌟",
                    "message", "You're close to level " + (stats.getLevel() + 1) + "! Keep going."
            ));
        }

        // Fallback nudge if nothing else
        if (nudges.isEmpty()) {
            nudges.add(Map.of(
                    "type", "content",
                    "emoji", "📚",
                    "message", "Upload notes to teach your tutor something new!"
            ));
        }

        return Map.of("nudges", nudges);
    }
}
