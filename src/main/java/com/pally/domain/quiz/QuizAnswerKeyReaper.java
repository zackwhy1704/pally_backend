package com.pally.domain.quiz;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Scale hygiene for the server-held quiz answer keys.
 *
 * <p>{@code quiz_answer_keys} gains ~5 rows per quiz, per student, per day; at
 * 1k–10k daily-active students that is millions of rows a year. The keys only
 * matter while the day's quiz can still be submitted, so anything past a short
 * retention window is dead weight. This daily job bulk-deletes stale keys so the
 * table stays small and grading lookups stay fast.
 *
 * <p>Retention is generous (default 7 days) so a quiz fetched late one day and
 * submitted the next still grades against the server key.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QuizAnswerKeyReaper {

    @Value("${quiz.answer-key-retention-days:7}")
    private int retentionDays;

    private final QuizAnswerKeyRepository answerKeyRepository;

    /** 03:15 Asia/Singapore daily — off-peak, after the consent reaper. */
    @Scheduled(cron = "0 15 3 * * *", zone = "Asia/Singapore")
    public void reap() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        try {
            int deleted = answerKeyRepository.deleteOlderThan(cutoff);
            if (deleted > 0) {
                log.info("[QuizAnswerKeyReaper] Deleted {} stale answer key(s) "
                        + "older than {}d", deleted, retentionDays);
            }
        } catch (Exception e) {
            log.error("[QuizAnswerKeyReaper] reap failed: {}", e.getMessage());
        }
    }
}
