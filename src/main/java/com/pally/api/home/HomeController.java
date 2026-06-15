package com.pally.api.home;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.quiz.FlashcardRepository;
import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/home")
@RequiredArgsConstructor
public class HomeController {

    private final UserRepository userRepository;
    private final AvatarRepository avatarRepository;
    private final FlashcardRepository flashcardRepository;

    @GetMapping("/nudges")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getNudges(
            @AuthenticationPrincipal String userId
    ) {
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

        return ResponseEntity.ok(ApiResponse.success(Map.of("nudges", nudges)));
    }
}
