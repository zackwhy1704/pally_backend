package com.pally.api.home;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.progress.UserRepository;
import com.pally.domain.progress.UserStats;
import com.pally.domain.quiz.FlashcardRepository;
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
        UserStats stats = userRepository.findById(userId).orElse(null);

        List<Map<String, String>> nudges = new ArrayList<>();

        // Streak nudge
        if (stats != null && stats.streakDays() > 0) {
            nudges.add(Map.of(
                "type", "streak",
                "emoji", "🔥",
                "message", "You're on a " + stats.streakDays() + "-day streak! Don't break it."
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
        if (stats != null && stats.xp() > 0 && stats.xp() % 100 < 20) {
            nudges.add(Map.of(
                "type", "quiz",
                "emoji", "🌟",
                "message", "You're close to level " + (stats.level() + 1) + "! Keep going."
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
