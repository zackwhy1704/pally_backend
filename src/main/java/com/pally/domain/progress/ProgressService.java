package com.pally.domain.progress;

import com.pally.domain.progress.dto.ProgressResponse;
import com.pally.domain.progress.dto.ReadingPingRequest;
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
import com.pally.shared.util.DurationClamp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

/**
 * Application service for the progress dashboard — owns all logic + repository
 * access so {@link ProgressController} stays a thin HTTP delegator. Returns the
 * exact response shapes the controller wraps, so the HTTP contract is identical.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProgressService {

    private static final ZoneId SGT = ZoneId.of("Asia/Singapore");

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
    private final ActivityLogService activityLogService;
    private final CurriculumTopicJpaRepository curriculumTopicRepo;
    private final WikiRepository wikiRepository;

    public ProgressResponse getProgress(String userId) {
        ProgressSummary summary = getProgressUseCase.execute(userId);
        return ProgressResponse.from(summary);
    }

    public Map<String, Object> logReading(String userId, ReadingPingRequest request) {
        if (request == null || request.avatarId() == null || request.avatarId().isBlank()) {
            throw new BusinessException("avatarId is required", 400);
        }
        // Ownership check — the avatar must belong to the caller.
        Avatar avatar = avatarRepository.findById(request.avatarId())
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(
                        "Avatar not found: " + request.avatarId(), 404));

        int durationSeconds = DurationClamp.clamp(request.durationSeconds());
        activityLogService.log(userId, avatar.getId(),
                ActivityLogService.TYPE_READING, durationSeconds, 0);

        return Map.of("avatarId", avatar.getId(), "durationSeconds", durationSeconds);
    }

    public Map<String, Object> getStudyPlan(String userId) {
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

        return Map.of("items", items, "upcomingTasks", upcomingTasks);
    }

    public void markStudyPlanTaskDone(String userId, String taskId) {
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

    public Map<String, Object> getStreak(String userId) {
        UserJpaEntity user = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        LocalDate today = LocalDate.now();
        LocalDate sixDaysAgo = today.minusDays(6);
        Set<LocalDate> activeDays = new HashSet<>(dailyDayRepo.daysSince(userId, sixDaysAgo));
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
        body.put("milestonesReached", streamMilestones(user.getStreakMilestonesReached()));
        body.put("ladder", StreakService.MILESTONES);
        return body;
    }

    public Map<String, Object> getToday(String userId) {
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
        return body;
    }

    public Map<String, Object> levelRoadmap(String userId) {
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
        return Map.of(
                "currentLevel", currentLevel,
                "maxLevel", ProgressSummary.MAX_LEVEL,
                "rewards", rewards);
    }

    public Map<String, Object> setDailyGoal(String userId, Map<String, Object> body) {
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
            throw new BusinessException("goalType must be QUIZ, XP, or MINUTES", 400);
        }
        // Sensible upper bounds — keeps the ring achievable.
        int max = switch (type) {
            case "XP" -> 200;
            case "MINUTES" -> 120;
            default -> 5;
        };
        if (target < 1 || target > max) {
            throw new BusinessException(
                    "goalTarget for " + type + " must be between 1 and " + max, 400);
        }
        UserJpaEntity user = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        user.setDailyGoalType(type);
        user.setDailyGoalTarget(target);
        userRepo.save(user);
        return Map.of("goalType", type, "goalTarget", target);
    }

    public Map<String, Object> getCoverage(String userId) {
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
            var rows = quizResultRepo.findAllTopicMasteryByAvatar(userId, avatar.getId());
            for (var r : rows) {
                if (((Number) r[1]).doubleValue() >= 0.7) mastered++;
            }
            // Never report mastered > total — clamp before aggregating.
            mastered = Math.min(mastered, total);

            overallTotal += total;
            overallMastered += mastered;

            String subject = avatar.getSubject() == null ? "OTHER" : avatar.getSubject().name();
            int[] agg = bySubject.computeIfAbsent(subject, k -> new int[2]);
            agg[0] += mastered;
            agg[1] += total;
        }

        List<Map<String, Object>> subjectDtos = new ArrayList<>();
        bySubject.forEach((subject, agg) -> subjectDtos.add(Map.of(
                "subject", subject,
                "mastered", agg[0],
                "total", agg[1])));
        // Highest-volume subjects first.
        subjectDtos.sort((a, b) -> Integer.compare((int) b.get("total"), (int) a.get("total")));

        return Map.of(
                "overall", Map.of("mastered", overallMastered, "total", overallTotal),
                "bySubject", subjectDtos);
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
}
