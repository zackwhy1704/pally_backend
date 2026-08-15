package com.pally.domain.boss;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.usecase.AvatarSlotGuard;
import com.pally.domain.boss.dto.BossAttackResponse;
import com.pally.domain.boss.dto.BossStateResponse;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.quiz.QuizAnswerKeyRepository;
import com.pally.domain.quiz.QuizQuestion;
import com.pally.domain.quiz.QuizService;
import com.pally.domain.quiz.dto.QuizQuestionResponse;
import com.pally.domain.quiz.port.QuizGeneratorPort;
import com.pally.domain.quiz.usecase.SubmitQuizAnswersUseCase;
import com.pally.domain.weakness.WeaknessProfileService;
import com.pally.domain.weakness.WeaknessSignalRepository;
import com.pally.domain.weakness.WeaknessSignalRepository.TopicMastery;
import com.pally.domain.weakness.WeaknessSignalService;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Phase 1 boss battles (v1): a detected weak topic materializes into a boss the
 * student fights with quiz questions targeting it. Server-authoritative — every
 * method here is the sole source of truth for HP/current-question/defeated
 * state; the client only renders the response.
 *
 * <p>v1 scope, explicitly: quiz_question_results is the ONLY weak-topic source
 * (via {@link WeaknessSignalRepository}, the same feed the daily quiz's
 * weak-first bias reads pre-pilot-flag). PROVE/module_progress signals do not
 * reach weak-topic detection yet — a known v1 limitation, not routed around
 * here. No live/multiplayer, no leaderboard — Phase 2/3.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BossBattleService {

    /// Fixed HP, in question-attempts — NOT a damage/points system. Each correct
    /// answer is one hit; a wrong answer never reduces HP (non-punitive).
    private static final int BOSS_HP = 3;

    private final AvatarRepository avatarRepository;
    private final WikiRepository wikiRepository;
    private final QuizGeneratorPort quizGeneratorPort;
    private final QuizService quizService;
    private final QuizAnswerKeyRepository answerKeyRepository;
    private final SubmitQuizAnswersUseCase submitQuizAnswersUseCase;
    private final WeaknessSignalRepository weaknessSignalRepository;
    private final WeaknessSignalService weaknessSignalService;
    private final WeaknessProfileService weaknessProfileService;
    private final BossInstanceRepository bossInstanceRepository;
    private final AvatarSlotGuard avatarSlotGuard;
    private final ObjectMapper objectMapper;

    /**
     * Returns the avatar's active boss, or detects a new one from the
     * student's weakest topic (quiz-only signal) and spawns it. Idempotent —
     * safe to poll; an existing undefeated boss is returned as-is, never
     * re-spawned. {@link BossStateResponse#none()} when there's no weak topic,
     * or no wiki content to generate questions from (v1 does not fall back to
     * the next-weakest topic — a known limitation, not a bug to route around).
     */
    public BossStateResponse getActiveOrDetect(String userId, String avatarId) {
        avatarSlotGuard.requireActive(avatarId, userId);

        var existing = bossInstanceRepository.findActiveByAvatarId(avatarId);
        if (existing.isPresent()) {
            return toResponse(existing.get(), avatarId);
        }

        List<TopicMastery> mastery = weaknessSignalRepository.findTopicMastery(userId, avatarId);
        List<TopicMastery> weak = weaknessSignalService.weakTopics(mastery);
        if (weak.isEmpty()) {
            return BossStateResponse.none();
        }
        String topicSlug = weak.get(0).topicSlug(); // weakest-first

        Avatar avatar = avatarRepository.findById(avatarId).orElse(null);
        if (avatar == null) return BossStateResponse.none();
        String wikiSourceId = avatar.getCorpusAvatarId() != null && !avatar.getCorpusAvatarId().isBlank()
                ? avatar.getCorpusAvatarId() : avatarId;

        WikiPage page = wikiRepository.findByAvatarIdAndSlug(wikiSourceId, topicSlug).orElse(null);
        if (page == null) {
            log.warn("[Boss] weak topic={} detected for avatar={} but no matching wiki page — "
                    + "cannot spawn a boss for it this round", topicSlug, avatarId);
            return BossStateResponse.none();
        }

        List<QuizQuestion> pool = quizGeneratorPort.generate(
                avatarId, List.of(page), page.getContentLanguage());
        if (pool.isEmpty()) {
            log.warn("[Boss] generator returned no questions for topic={} avatar={}",
                    topicSlug, avatarId);
            return BossStateResponse.none();
        }

        // Persists the server answer key via the SAME chokepoint the daily quiz
        // uses (QuizService.serveGradable) — grade integrity applies identically.
        List<QuizQuestionResponse> served = quizService.serveGradable(avatarId, pool);

        int hp = Math.min(BOSS_HP, pool.size());
        BossInstance boss = BossInstance.spawn(userId, avatarId, topicSlug, toJson(pool), hp);
        BossInstance saved = bossInstanceRepository.save(boss);
        log.info("[Boss] spawned boss={} topic={} avatar={} hp={}",
                saved.id(), topicSlug, avatarId, hp);

        return new BossStateResponse(true, saved.id(), saved.topicSlug(),
                saved.hpRemaining(), saved.hpMax(), false, false, served.get(0));
    }

    /**
     * One attack: grades {@code questionId}/{@code selectedIndex} against the
     * SERVER key (never the client), advances the boss state, and — through
     * {@link SubmitQuizAnswersUseCase#recordSingleQuestionResult} — writes the
     * SAME quiz_question_results + learning_event rows a normal quiz answer
     * would (no second write path for the same fact). On defeat: triggers the
     * existing mastery-update call and flips the (minimal) reward-unlocked flag.
     */
    public BossAttackResponse attack(String bossId, String userId, String avatarId,
                                      String questionId, Integer selectedIndex) {
        avatarSlotGuard.requireActive(avatarId, userId);
        if (questionId == null || questionId.isBlank() || selectedIndex == null) {
            throw new BusinessException("questionId and selectedIndex are required", 400);
        }

        BossInstance boss = bossInstanceRepository.findById(bossId)
                .orElseThrow(() -> new BusinessException("Boss not found: " + bossId, 404));
        // IDOR guard: 404 (not 403) so we don't reveal another user's boss exists.
        if (!boss.userId().equals(userId) || !boss.avatarId().equals(avatarId)) {
            throw new BusinessException("Boss not found: " + bossId, 404);
        }
        if (boss.defeated()) {
            throw new BusinessException("This boss is already defeated", 400);
        }

        List<QuizQuestion> pool = fromJson(boss.questionPoolJson());
        QuizQuestion expected = pool.get(boss.currentIndex() % pool.size());
        if (!expected.id().equals(questionId)) {
            throw new BusinessException(
                    "This isn't the current question for this boss — battle state out of sync", 400);
        }

        var key = answerKeyRepository.findByQuestionIds(Set.of(questionId)).get(questionId);
        // Fail-closed: no persisted server key means no verified grade — never
        // award a hit the server can't back.
        boolean hitLanded = key != null && key.correctIndex() == selectedIndex;

        submitQuizAnswersUseCase.recordSingleQuestionResult(
                userId, avatarId, questionId, boss.topicSlug(), hitLanded);

        BossInstance updated = bossInstanceRepository.save(boss.afterAttack(hitLanded));

        if (updated.defeated()) {
            log.info("[Boss] defeated boss={} topic={} avatar={}",
                    updated.id(), updated.topicSlug(), avatarId);
            if (weaknessProfileService.isEnabled()) {
                try {
                    weaknessProfileService.onMasteryUpdated(userId, avatarId);
                } catch (Exception e) {
                    log.warn("[Boss] mastery-update trigger failed (non-fatal): {}", e.getMessage());
                }
            }
        }

        return new BossAttackResponse(toResponse(updated, avatarId), hitLanded);
    }

    private BossStateResponse toResponse(BossInstance boss, String avatarId) {
        if (boss.defeated()) {
            return new BossStateResponse(true, boss.id(), boss.topicSlug(),
                    boss.hpRemaining(), boss.hpMax(), true, boss.rewardUnlocked(), null);
        }
        List<QuizQuestion> pool = fromJson(boss.questionPoolJson());
        QuizQuestion current = pool.get(boss.currentIndex() % pool.size());
        QuizQuestionResponse served = quizService.serveGradable(avatarId, List.of(current)).get(0);
        return new BossStateResponse(true, boss.id(), boss.topicSlug(),
                boss.hpRemaining(), boss.hpMax(), false, false, served);
    }

    private String toJson(List<QuizQuestion> pool) {
        try {
            return objectMapper.writeValueAsString(pool);
        } catch (Exception e) {
            throw new BusinessException("Failed to persist boss question pool", 500);
        }
    }

    private List<QuizQuestion> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<QuizQuestion>>() {});
        } catch (Exception e) {
            throw new BusinessException("Corrupt boss question pool", 500);
        }
    }
}
