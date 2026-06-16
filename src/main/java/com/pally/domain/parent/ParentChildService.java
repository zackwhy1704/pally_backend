package com.pally.domain.parent;

import com.pally.domain.parent.dto.ParentDashboardResponse;
import com.pally.domain.parent.dto.ParentDashboardResponse.SubjectMasteryDto;
import com.pally.domain.parent.dto.ParentDashboardResponse.WeakAreaDto;
import com.pally.domain.parent.dto.WeeklyReportDetail;
import com.pally.domain.parent.dto.WeeklyReportSummary;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.progress.ActivityLogService;
import com.pally.domain.progress.WeeklyReportService;
import com.pally.infrastructure.persistence.activity.ActivityLogJpaRepository;
import com.pally.infrastructure.persistence.assignment.AssignmentJpaEntity;
import com.pally.infrastructure.persistence.assignment.AssignmentJpaRepository;
import com.pally.infrastructure.persistence.consent.ConsentRecordJpaEntity;
import com.pally.infrastructure.persistence.consent.ConsentRecordJpaRepository;
import com.pally.infrastructure.persistence.module.LearningModuleJpaEntity;
import com.pally.infrastructure.persistence.module.LearningModuleJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaRepository;
import com.pally.infrastructure.persistence.star.StarAwardLogJpaEntity;
import com.pally.infrastructure.persistence.star.StarAwardLogJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Application service for parent-to-child operations — owns all logic + repo
 * access so {@link ParentChildController} stays a thin HTTP delegator. Every
 * method enforces the parent-of-child guard and returns the exact response
 * shapes the controller wraps, so the HTTP contract is identical.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParentChildService {

    private static final int STAR_AWARD_MAX_PER_AWARD = 100;
    private static final int STAR_AWARD_DAILY_CAP = 300;

    private final UserJpaRepository userRepo;
    private final ActivityLogJpaRepository activityRepo;
    private final ActivityLogService activityLogService;
    private final QuizQuestionResultJpaRepository quizResultRepo;
    private final AvatarRepository avatarRepository;
    private final WikiRepository wikiRepository;
    private final WeeklyReportService weeklyReportService;
    private final AssignmentJpaRepository assignmentRepo;
    private final StarAwardLogJpaRepository starAwardLogRepo;
    private final LearningModuleJpaRepository moduleRepo;
    private final ConsentRecordJpaRepository consentRecordRepo;

    // ── Access guard ────────────────────────────────────────────────────

    private void assertIsChild(String parentId, String childId) {
        UserJpaEntity child = userRepo.findById(childId)
                .orElseThrow(() -> new BusinessException("Child not found", 404));
        if (!parentId.equals(child.getParentId())) {
            throw new BusinessException("Access denied", 403);
        }
    }

    // ── Dashboard ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ParentDashboardResponse getDashboard(String parentId, String childId) {
        assertIsChild(parentId, childId);
        UserJpaEntity child = userRepo.findById(childId)
                .orElseThrow(() -> new BusinessException("Child not found", 404));

        Instant weekAgo = Instant.now().minus(Duration.ofDays(7));
        int sessions = orZero(activityRepo.countSince(childId, weekAgo));
        int xpThisWeek = orZero(activityRepo.sumXpSince(childId, weekAgo));
        int minutesThisWeek = orZero(activityRepo.sumMinutesBetween(
                childId, weekAgo, Instant.now()));
        List<Integer> weekMinutes = activityLogService.minutesPerDayLast7(childId);

        List<SubjectMasteryDto> subjects = weeklyReportService.computeSubjectMastery(childId);

        List<WeakAreaDto> weakAreas = quizResultRepo.findWeakestTopics(childId, 5)
                .stream()
                .map(r -> new WeakAreaDto((String) r[0], ((Number) r[1]).doubleValue()))
                .toList();

        List<String> reviewTopics = new ArrayList<>();
        for (Avatar a : avatarRepository.findByUserId(childId)) {
            wikiRepository.findReviewRequired(a.getId())
                    .forEach(p -> reviewTopics.add(p.getTitle()));
        }

        return new ParentDashboardResponse(
                sessions, minutesThisWeek, xpThisWeek,
                child.getLevel(), child.getStreakDays(),
                subjects, weekMinutes, weakAreas,
                child.isScreenTimeEnabled(), child.getScreenTimeMinutes(),
                reviewTopics);
    }

    // ── Reports ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> listReports(String parentId, String childId) {
        assertIsChild(parentId, childId);
        List<WeeklyReportSummary> reports = weeklyReportService.listReports(childId);
        return Map.of("reports", reports);
    }

    @Transactional(readOnly = true)
    public WeeklyReportDetail getReport(String parentId, String childId, String weekId) {
        assertIsChild(parentId, childId);
        return weeklyReportService.getReport(childId, weekId);
    }

    // ── Modules ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listModules(String parentId, String childId) {
        assertIsChild(parentId, childId);
        List<Avatar> avatars = avatarRepository.findByUserId(childId);
        List<Map<String, Object>> modules = new ArrayList<>();
        for (Avatar avatar : avatars) {
            List<LearningModuleJpaEntity> avatarModules = moduleRepo.findByAvatarId(avatar.getId());
            for (LearningModuleJpaEntity m : avatarModules) {
                Map<String, Object> dto = new HashMap<>();
                dto.put("moduleId", m.getId());
                dto.put("avatarId", m.getAvatarId());
                dto.put("title", m.getTitle());
                dto.put("stage", m.getStage());
                dto.put("tier", m.getTier());
                dto.put("masteryPct", m.getMasteryPct());
                dto.put("subject", avatar.getSubject().name());
                modules.add(dto);
            }
        }
        return modules;
    }

    // ── P-3: Assign revision ────────────────────────────────────────────

    @Transactional
    public Map<String, Object> assignRevision(String parentId, String childId,
                                              Map<String, Object> body) {
        assertIsChild(parentId, childId);

        @SuppressWarnings("unchecked")
        List<String> moduleIds = (List<String>) body.get("moduleIds");
        String dueDate = (String) body.get("dueDate");
        String title = (String) body.get("title");

        if (moduleIds == null || moduleIds.isEmpty()) {
            throw new BusinessException("moduleIds is required", 400);
        }
        if (dueDate == null) {
            throw new BusinessException("dueDate is required", 400);
        }
        if (title == null || title.isBlank()) {
            title = "Parent-assigned revision";
        }

        AssignmentJpaEntity assignment = new AssignmentJpaEntity();
        assignment.setId(IdGenerator.newId());
        assignment.setClassId(null); // parent-assigned, no class
        assignment.setStudentId(childId);
        assignment.setTitle(title);
        assignment.setType(AssignmentJpaEntity.TYPE_REVISION);
        assignment.setModuleIds(String.join(",", moduleIds));
        assignment.setDueDate(Instant.parse(dueDate));
        assignment.setCreatedBy(parentId);
        assignment.setCreatedAt(Instant.now());
        assignmentRepo.save(assignment);

        log.info("[ParentChild] Parent={} assigned revision to child={} assignmentId={}",
                parentId, childId, assignment.getId());

        return Map.of("assignmentId", assignment.getId(), "title", assignment.getTitle());
    }

    // ── P-3: Award stars ────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> awardStars(String parentId, String childId,
                                          Map<String, Object> body) {
        assertIsChild(parentId, childId);

        Number amountNum = (Number) body.get("amount");
        String note = (String) body.get("note");

        if (amountNum == null || amountNum.intValue() <= 0) {
            throw new BusinessException("amount must be a positive integer", 400);
        }
        int amount = amountNum.intValue();
        if (amount > STAR_AWARD_MAX_PER_AWARD) {
            throw new BusinessException(
                    "Maximum " + STAR_AWARD_MAX_PER_AWARD + " stars per award", 400);
        }

        // Daily cap check
        Instant dayStart = LocalDate.now(com.pally.shared.util.PallyTime.SGT)
                .atStartOfDay(com.pally.shared.util.PallyTime.SGT).toInstant();
        int awardedToday = starAwardLogRepo.sumAmountByParentIdSince(parentId, dayStart);
        if (awardedToday + amount > STAR_AWARD_DAILY_CAP) {
            throw new BusinessException(
                    "Daily award cap reached (" + STAR_AWARD_DAILY_CAP
                            + " stars). Awarded today: " + awardedToday, 422);
        }

        // Atomic add
        UserJpaEntity child = userRepo.findById(childId)
                .orElseThrow(() -> new BusinessException("Child not found", 404));
        child.setStars(child.getStars() + amount);
        userRepo.save(child);

        // Audit log
        StarAwardLogJpaEntity logEntry = new StarAwardLogJpaEntity();
        logEntry.setId(IdGenerator.newId());
        logEntry.setParentId(parentId);
        logEntry.setChildId(childId);
        logEntry.setAmount(amount);
        logEntry.setNote(note);
        logEntry.setAwardedAt(Instant.now());
        starAwardLogRepo.save(logEntry);

        log.info("[ParentChild] Parent={} awarded {} stars to child={}",
                parentId, amount, childId);

        return Map.of("newBalance", child.getStars(), "awarded", amount);
    }

    // ── Family goal ─────────────────────────────────────────────────────

    @Transactional
    public void setGoal(String parentId, String childId, Map<String, Object> body) {
        assertIsChild(parentId, childId);
        UserJpaEntity child = userRepo.findById(childId)
                .orElseThrow(() -> new BusinessException("Child not found", 404));

        // Store the entire body as JSON
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(body);
            child.setFamilyGoalJson(json);
            userRepo.save(child);
        } catch (Exception e) {
            throw new BusinessException("Invalid goal data", 400);
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getGoal(String parentId, String childId) {
        assertIsChild(parentId, childId);
        UserJpaEntity child = userRepo.findById(childId)
                .orElseThrow(() -> new BusinessException("Child not found", 404));

        String json = child.getFamilyGoalJson();
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> goal = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, Map.class);
            return goal;
        } catch (Exception e) {
            return Map.of();
        }
    }

    // ── Consent ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> getConsent(String parentId, String childId) {
        assertIsChild(parentId, childId);
        Optional<ConsentRecordJpaEntity> record = consentRecordRepo
                .findFirstByChildIdAndParentIdOrderByCreatedAtDesc(childId, parentId);
        boolean hasConsent = record.isPresent() && record.get().getWithdrawnAt() == null;
        return Map.of(
                "hasConsent", hasConsent,
                "consentId", record.map(ConsentRecordJpaEntity::getId).orElse(""));
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private int orZero(Integer i) {
        return i == null ? 0 : i;
    }
}
