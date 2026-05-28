package com.pally.domain.progress;

import com.pally.infrastructure.persistence.activity.ActivityLogJpaEntity;
import com.pally.infrastructure.persistence.activity.ActivityLogJpaRepository;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/// Records user actions for the progress dashboard (week minutes chart,
/// activity feed). Always best-effort — never let logging failures break
/// the calling use case.
@Service
@RequiredArgsConstructor
@Slf4j
public class ActivityLogService {

    public static final String TYPE_QUIZ = "QUIZ";
    public static final String TYPE_CHAT = "CHAT";
    public static final String TYPE_UPLOAD = "UPLOAD";
    public static final String TYPE_PHOTO = "PHOTO";
    public static final String TYPE_FLASHCARD = "FLASHCARD";

    private final ActivityLogJpaRepository repo;

    @Transactional
    public void log(String userId, String avatarId, String type,
                    int durationSeconds, int xpEarned) {
        try {
            ActivityLogJpaEntity e = new ActivityLogJpaEntity();
            e.setId(IdGenerator.newId());
            e.setUserId(userId);
            e.setAvatarId(avatarId);
            e.setActivityType(type);
            e.setDurationSeconds(durationSeconds);
            e.setXpEarned(xpEarned);
            e.setCreatedAt(Instant.now());
            repo.save(e);
        } catch (Exception ex) {
            log.warn("[ActivityLog] failed user={} type={}: {}",
                    userId, type, ex.getMessage());
        }
    }

    /// Returns minutes-per-day for the last 7 days as Monday→Sunday list.
    /// Used by the progress dashboard bar chart.
    public List<Integer> minutesPerDayLast7(String userId) {
        List<Integer> result = new ArrayList<>(7);
        LocalDate today = LocalDate.now();
        // Start from 6 days ago up to today inclusive
        for (int i = 6; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            Instant from = day.atStartOfDay().toInstant(ZoneOffset.UTC);
            Instant to = day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            Integer mins = repo.sumMinutesBetween(userId, from, to);
            result.add(mins != null ? mins : 0);
        }
        return result;
    }

    /// Minutes per day across [start, end] inclusive (always 7 entries when
    /// called with a full ISO week). Used by the weekly report detail.
    public List<Integer> minutesPerDayBetween(
            String userId, LocalDate start, LocalDate end) {
        List<Integer> result = new ArrayList<>();
        for (LocalDate day = start;
             !day.isAfter(end);
             day = day.plusDays(1)) {
            Instant from = day.atStartOfDay().toInstant(ZoneOffset.UTC);
            Instant to = day.plusDays(1)
                    .atStartOfDay().toInstant(ZoneOffset.UTC);
            Integer mins = repo.sumMinutesBetween(userId, from, to);
            result.add(mins != null ? mins : 0);
        }
        return result;
    }
}
