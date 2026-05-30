package com.pally.api.progress;

import com.pally.api.progress.dto.ProgressResponse;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.progress.ActivityLogService;
import com.pally.domain.progress.LevelRewards;
import com.pally.domain.progress.ProgressSummary;
import com.pally.domain.progress.StreakService;
import com.pally.domain.progress.usecase.GetProgressUseCase;
import com.pally.domain.quiz.FlashCard;
import com.pally.domain.quiz.FlashcardRepository;
import com.pally.infrastructure.persistence.activity.ActivityLogJpaRepository;
import com.pally.infrastructure.persistence.activity.DailyActivityDayJpaRepository;
import com.pally.infrastructure.persistence.avatar.AvatarJpaRepository;
import com.pally.infrastructure.persistence.curriculum.CurriculumTopicJpaRepository;
import com.pally.infrastructure.persistence.progress.StudyPlanCompletionJpaEntity;
import com.pally.infrastructure.persistence.progress.StudyPlanCompletionJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/progress")
@RequiredArgsConstructor
@Slf4j
public class ProgressController {

    private final GetProgressUseCase getProgressUseCase;
    private final AvatarRepository avatarRepository;
    private final FlashcardRepository flashcardRepository;
    private final QuizQuestionResultJpaRepository quizResultRepo;
    private final StreakService streakService;
    private final DailyActivityDayJpaRepository dailyDayRepo;
    private final UserJpaRepository userRepo;
    private final ActivityLogJpaRepository activityLogRepo;
    private final AvatarJpaRepository avatarJpaRepo;
    private final StudyPlanCompletionJpaRepository studyPlanCompletionRepo;

    private static final ZoneId SGT = ZoneId.of("Asia/Singapore");
    private final CurriculumTopicJpaRepository curriculumTopicRepo;
    private final WikiRepository wikiRepository;

    @GetMapping
    public ResponseEntity<ApiResponse<ProgressResponse>> getProgress(
            @AuthenticationPrincipal String userId
    ) {
        ProgressSummary summary = getProgressUseCase.execute(userId);
        return ResponseEntity.ok(ApiResponse.success(ProgressResponse.from(summary)));
    }

    /// Adaptive study plan. Task IDs are deterministic: "{avatarId}:{type}:{sgtDate}"
    /// so the client can mark them done and the server can exclude completed tasks
    /// on subsequent fetches within the same SGT day.
    @GetMapping("/study-plan")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStudyPlan(
            @AuthenticationPrincipal String userId
    ) {
        String sgtDate = LocalDate.now(SGT).toString(); // "2025-01-15"
        Instant dayStart = LocalDate.now(SGT).atStartOfDay(SGT).toInstant();
        Instant dayEnd = dayStart.plus(1, java.time.temporal.ChronoUnit.DAYS);

        // Load tasks completed today so we can exclude them.
        Set<String> completedToday = studyPlanCompletionRepo
                .findCompletedKeysToday(userId, dayStart, dayEnd);

        List<Map<String, Object>> items = new ArrayList<>();
        List<Avatar> avatars = avatarRepository.findByUserId(userId);

        for (Avatar avatar : avatars) {
            // Due flashcards
            List<FlashCard> due = flashcardRepository.findDueByAvatarId(avatar.getId());
            if (!due.isEmpty()) {
                String taskKey = avatar.getId() + ":flashcard:" + sgtDate;
                boolean done = completedToday.contains(taskKey);
                Map<String, Object> task = new HashMap<>();
                task.put("id", taskKey);
                task.put("title", "Review " + due.size() + " flashcard"
                        + (due.size() == 1 ? "" : "s") + " — " + avatar.getName());
                task.put("type", "flashcard");
                task.put("avatarId", avatar.getId());
                task.put("done", done);
                task.put("reason", due.size() + " card" + (due.size() == 1 ? "" : "s")
                        + " scheduled for review today");
                items.add(task);
            }

            // Daily quiz if not taken today
            boolean takenToday = false;
            try {
                Boolean b = quizResultRepo.takenToday(userId, avatar.getId());
                takenToday = b != null && b;
            } catch (Exception ignored) {}

            if (!takenToday) {
                String taskKey = avatar.getId() + ":quiz:" + sgtDate;
                boolean done = completedToday.contains(taskKey);
                Map<String, Object> task = new HashMap<>();
                task.put("id", taskKey);
                task.put("title", "Daily quiz — " + avatar.getName());
                task.put("type", "quiz");
                task.put("avatarId", avatar.getId());
                task.put("done", done);
                task.put("reason", "Keep your daily streak going");
                items.add(task);
            }
        }

        if (items.isEmpty()) {
            String taskKey = "info:" + sgtDate;
            Map<String, Object> task = new HashMap<>();
            task.put("id", taskKey);
            task.put("title", "Create your first tutor to get started");
            task.put("type", "info");
            task.put("avatarId", "");
            task.put("done", false);
            task.put("reason", "");
            items.add(task);
        }

        // Upcoming weak-topic hints (not actionable today items)
        List<Map<String, Object>> upcomingTasks = new ArrayList<>();
        try {
            List<Object[]> weakTopics = quizResultRepo.findWeakestTopics(userId, 3);
            for (Object[] row : weakTopics) {
                String topic = (String) row[0];
                int pct = (int) Math.round(((Number) row[1]).doubleValue() * 100);
                upcomingTasks.add(Map.of(
                        "day", "Soon",
                        "title", "Practice " + topic + " (" + pct + "% mastery)"
                ));
            }
        } catch (Exception ignored) {}

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "items", items,
                "upcomingTasks", upcomingTasks
        )));
    }

    /// Persists a study-plan task completion keyed by the stable task ID
    /// ({avatarId}:{type}:{sgtDate}). Idempotent — re-marking the same task
    /// is a no-op (primary key conflict silently ignored by upsert semantics).
    @PostMapping("/study-plan/{taskId}/done")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markStudyPlanTaskDone(
            @AuthenticationPrincipal String userId,
            @PathVariable String taskId) {
        var pk = new StudyPlanCompletionJpaEntity.PK(userId, taskId);
        if (!studyPlanCompletionRepo.existsById(pk)) {
            var completion = new StudyPlanCompletionJpaEntity();
            completion.setUserId(userId);
            completion.setTaskKey(taskId);
            completion.setCompletedAt(Instant.now());
            studyPlanCompletionRepo.save(completion);
        }
        log.info("[StudyPlan] user={} marked task={} done", userId, taskId);
    }

    /// Everything the streak card needs in one round-trip — the 7-dot strip
    /// is rendered from {@code last7} (oldest→newest, today last) so the
    /// client doesn't need to align to a calendar week.
    @GetMapping("/streak")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStreak(
            @AuthenticationPrincipal String userId) {
        UserJpaEntity user = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        LocalDate today = LocalDate.now();
        LocalDate sixDaysAgo = today.minusDays(6);
        Set<LocalDate> activeDays = new HashSet<>(
                dailyDayRepo.daysSince(userId, sixDaysAgo));
        List<Boolean> last7 = new ArrayList<>(7);
        for (int i = 6; i >= 0; i--) {
            last7.add(activeDays.contains(today.minusDays(i)));
        }
        int streak = user.getStreakDays();
        int next = streakService.nextMilestone(streak);

        Map<String, Object> body = new HashMap<>();
        body.put("streakDays", streak);
        body.put("longestStreak", user.getLongestStreak());
        body.put("freezes", user.getStreakFreezes());
        body.put("last7", last7);
        body.put("nextMilestone", next);
        body.put("daysToMilestone", Math.max(0, next - streak));
        body.put("milestonesReached",
                streamMilestones(user.getStreakMilestonesReached()));
        body.put("ladder", StreakService.MILESTONES);
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    private List<Integer> streamMilestones(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<Integer> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            try {
                out.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    /// Today's goal ring data. {@code goalProgress} is computed live from
    /// activity_log so changing the goal type doesn't strand stale counts.
    @GetMapping("/today")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getToday(
            @AuthenticationPrincipal String userId) {
        UserJpaEntity user = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        LocalDate today = LocalDate.now();
        Instant from = today.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = today.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        String type = user.getDailyGoalType();
        int target = Math.max(1, user.getDailyGoalTarget());
        int progress = switch (type) {
            case "XP" -> {
                Integer xp = activityLogRepo.sumXpBetween(userId, from, to);
                yield xp == null ? 0 : xp;
            }
            case "MINUTES" -> {
                Integer m = activityLogRepo.sumMinutesBetween(userId, from, to);
                yield m == null ? 0 : m;
            }
            default -> {
                Integer q = activityLogRepo.countByTypeBetween(
                        userId, ActivityLogService.TYPE_QUIZ, from, to);
                yield q == null ? 0 : q;
            }
        };
        boolean met = progress >= target;

        Map<String, Object> body = new HashMap<>();
        body.put("goalType", type);
        body.put("goalTarget", target);
        body.put("goalProgress", Math.min(progress, target * 5)); // hard cap for sanity
        body.put("met", met);
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    /// Static catalog of every level reward, tagged with which ones the
    /// caller has already crossed. Powers the LevelRoadmap screen.
    @GetMapping("/level-roadmap")
    public ResponseEntity<ApiResponse<Map<String, Object>>> levelRoadmap(
            @AuthenticationPrincipal String userId) {
        UserJpaEntity user = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        int currentLevel = user.getLevel();
        List<Map<String, Object>> rewards = LevelRewards.all().stream()
                .map(r -> Map.<String, Object>of(
                        "level", r.level(),
                        "label", r.label(),
                        "kind", r.kind().name(),
                        "unlocked", currentLevel >= r.level()))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "currentLevel", currentLevel,
                "maxLevel", ProgressSummary.MAX_LEVEL,
                "rewards", rewards)));
    }

    /// Persist the child's chosen goal. Validation keeps targets sane so a
    /// fat-fingered 999-XP goal can't soft-lock the ring.
    @PostMapping("/daily-goal")
    public ResponseEntity<ApiResponse<Map<String, Object>>> setDailyGoal(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, Object> body) {
        String type = body.get("goalType") == null
                ? "QUIZ"
                : body.get("goalType").toString().toUpperCase();
        int target;
        try {
            target = ((Number) body.getOrDefault("goalTarget", 1)).intValue();
        } catch (ClassCastException e) {
            throw new BusinessException("goalTarget must be a number", 400);
        }
        if (!List.of("QUIZ", "XP", "MINUTES").contains(type)) {
            throw new BusinessException(
                    "goalType must be QUIZ, XP, or MINUTES", 400);
        }
        // Sensible upper bounds — keeps the ring achievable. A kid who
        // wants more can stack multiple sessions.
        int max = switch (type) {
            case "XP" -> 200;
            case "MINUTES" -> 120;
            default -> 5;
        };
        if (target < 1 || target > max) {
            throw new BusinessException(
                    "goalTarget for " + type + " must be between 1 and " + max,
                    400);
        }
        UserJpaEntity user = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        user.setDailyGoalType(type);
        user.setDailyGoalTarget(target);
        userRepo.save(user);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "goalType", type,
                "goalTarget", target)));
    }

    /// Aggregate mastery (Khan-style North Star) across every avatar the
    /// user owns. Avatar with a curriculum attached counts against its
    /// curriculum_topics list; otherwise total = wiki_page count, matching
    /// what {@code /quiz/status} already returns per-avatar.
    @GetMapping("/coverage")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCoverage(
            @AuthenticationPrincipal String userId) {
        var avatars = avatarJpaRepo.findByUserId(userId);

        int overallTotal = 0;
        int overallMastered = 0;
        Map<String, int[]> bySubject = new HashMap<>(); // subject → [mastered, total]

        for (var avatar : avatars) {
            int total;
            String curriculumId = avatar.getCurriculumId();
            if (curriculumId != null && !curriculumId.isBlank()) {
                total = curriculumTopicRepo
                        .findByCurriculumIdOrderBySequenceAsc(curriculumId)
                        .size();
            } else {
                total = wikiRepository.findByAvatarId(avatar.getId()).size();
            }
            int mastered = 0;
            var rows = quizResultRepo
                    .findAllTopicMasteryByAvatar(userId, avatar.getId());
            for (var r : rows) {
                if (((Number) r[1]).doubleValue() >= 0.7) mastered++;
            }
            // A curriculum can have more topics than the kid has tackled, so
            // never report mastered > total — clamp before aggregating.
            mastered = Math.min(mastered, total);

            overallTotal += total;
            overallMastered += mastered;

            String subject = avatar.getSubject() == null
                    ? "OTHER"
                    : avatar.getSubject().name();
            int[] agg = bySubject.computeIfAbsent(subject, k -> new int[2]);
            agg[0] += mastered;
            agg[1] += total;
        }

        List<Map<String, Object>> subjectDtos = new ArrayList<>();
        bySubject.forEach((subject, agg) -> subjectDtos.add(Map.of(
                "subject", subject,
                "mastered", agg[0],
                "total", agg[1])));
        // Highest-volume subjects first so the UI surfaces the biggest piles.
        subjectDtos.sort((a, b) -> Integer.compare(
                (int) b.get("total"), (int) a.get("total")));

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "overall", Map.of(
                        "mastered", overallMastered,
                        "total", overallTotal),
                "bySubject", subjectDtos)));
    }
}
