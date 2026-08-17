package com.pally.domain.classroom;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.centre.CentreAccessService;
import com.pally.domain.centre.ClassCrudService;
import com.pally.domain.classroom.ClassroomEventBus.ClassroomStreamEvent;
import com.pally.domain.classroom.dto.ClassroomAttackResponse;
import com.pally.domain.classroom.dto.ClassroomJoinResponse;
import com.pally.domain.classroom.dto.ClassroomStateResponse;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.quiz.QuizAnswerKeyRepository;
import com.pally.domain.quiz.QuizQuestion;
import com.pally.domain.quiz.QuizService;
import com.pally.domain.quiz.dto.QuizQuestionResponse;
import com.pally.domain.quiz.port.QuizGeneratorPort;
import com.pally.domain.quiz.usecase.SubmitQuizAnswersUseCase;
import com.pally.domain.weakness.WeaknessProfileService;
import com.pally.infrastructure.ai.ModerationService;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Phase 2: a teacher-created live shared boss battle for a whole class.
 * Reuses {@link ClassroomSession}'s BossInstance-mirrored HP/question-pool
 * mechanics and the SAME grade-integrity/answer-exposure/write-path
 * guarantees Phase 1's {@code BossBattleService} established — nothing here
 * invents a second grading or persistence story.
 *
 * <p>Participant identity is intentionally NOT modeled as a persisted
 * resource: a student's nickname lives only in {@link ClassroomEventBus} for
 * the session's lifetime. Every nickname is screened through
 * {@link ModerationService#screenInput} before being accepted — a nickname is
 * exactly as user-to-user-visible as a group name, so it gets exactly the
 * same gate (flagged() &amp;&amp; isHighSeverity() blocks), never skipped
 * because it's "just a nickname."
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClassroomSessionService {

    /// Matches Phase 1's BossBattleService.BOSS_HP — same mechanics, shared scope.
    private static final int BOSS_HP = 3;

    private final CentreAccessService centreAccessService;
    private final ClassCrudService classCrudService;
    private final WikiRepository wikiRepository;
    private final QuizGeneratorPort quizGeneratorPort;
    private final QuizService quizService;
    private final QuizAnswerKeyRepository answerKeyRepository;
    private final SubmitQuizAnswersUseCase submitQuizAnswersUseCase;
    private final WeaknessProfileService weaknessProfileService;
    private final ModerationService moderationService;
    private final AvatarRepository avatarRepository;
    private final ClassroomSessionRepository sessionRepository;
    private final ClassroomEventBus eventBus;
    private final ObjectMapper objectMapper;

    // ── Teacher: create / start / end / live state ──────────────────────

    public ClassroomStateResponse create(String userId, String orgId, String classId, String wikiPageId) {
        centreAccessService.ensureStaff(userId, orgId);
        var cls = classCrudService.getClass(orgId, classId); // var: no infra import needed here
        String corpusAvatarId = cls.getCorpusAvatarId();
        if (corpusAvatarId == null || corpusAvatarId.isBlank()) {
            throw new BusinessException("This class has no compiled material yet", 400);
        }

        WikiPage page = wikiRepository.findById(wikiPageId)
                .filter(p -> corpusAvatarId.equals(p.getAvatarId()))
                .orElseThrow(() -> new BusinessException(
                        "Wiki page not found in this class's material", 404));

        List<QuizQuestion> pool = quizGeneratorPort.generate(
                corpusAvatarId, List.of(page), page.getContentLanguage());
        if (pool.isEmpty()) {
            throw new BusinessException("Could not generate questions from this material", 422);
        }
        // Persists the server answer key via the SAME chokepoint the daily quiz
        // and solo boss battle use — grade integrity applies identically.
        quizService.serveGradable(corpusAvatarId, pool);

        int hp = Math.min(BOSS_HP, pool.size());
        String joinCode = uniqueJoinCode();
        ClassroomSession session = ClassroomSession.create(
                classId, userId, corpusAvatarId, joinCode, page.getSlug(), toJson(pool), hp);
        ClassroomSession saved = sessionRepository.save(session);
        log.info("[Classroom] created session={} class={} topic={} hp={} code={}",
                saved.id(), classId, page.getSlug(), hp, joinCode);
        return toStateResponse(saved, 0);
    }

    public ClassroomStateResponse start(String userId, String orgId, String sessionId) {
        ClassroomSession session = requireTeacherSession(userId, orgId, sessionId);
        if (!ClassroomSession.STATUS_CREATED.equals(session.status())) {
            throw new BusinessException("Session already started or ended", 400);
        }
        ClassroomSession started = sessionRepository.save(session.started());
        eventBus.publish(sessionId, "started", "{}");
        log.info("[Classroom] started session={}", sessionId);
        return toStateResponse(started, participantCount(sessionId));
    }

    public ClassroomStateResponse end(String userId, String orgId, String sessionId) {
        ClassroomSession session = requireTeacherSession(userId, orgId, sessionId);
        if (ClassroomSession.STATUS_ENDED.equals(session.status())) {
            return toStateResponse(session, 0);
        }
        ClassroomSession ended = sessionRepository.save(session.ended());
        eventBus.publish(sessionId, "ended", "{}");
        // Irrecoverably drops nicknames + closes every participant's stream —
        // this is THE moment ephemeral identity stops existing anywhere.
        eventBus.forgetSession(sessionId);
        log.info("[Classroom] ended session={}", sessionId);
        return toStateResponse(ended, 0);
    }

    public ClassroomStateResponse getLiveState(String userId, String orgId, String sessionId) {
        ClassroomSession session = requireTeacherSession(userId, orgId, sessionId);
        return toStateResponse(session, participantCount(sessionId));
    }

    // ── Student: join / attack / stream ─────────────────────────────────

    /**
     * Screens the nickname BEFORE accepting it — a rejected nickname never
     * reaches {@link ClassroomEventBus}, never gets a participantToken, and
     * is never broadcast to anyone.
     */
    public ClassroomJoinResponse join(String userId, String avatarId, String joinCode, String nickname) {
        if (nickname == null || nickname.isBlank() || nickname.length() > 24) {
            throw new BusinessException("Nickname is required (1-24 chars)", 400);
        }
        Avatar avatar = avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException("Avatar not found", 404));

        ClassroomSession session = sessionRepository.findLiveByJoinCode(joinCode)
                .orElseThrow(() -> new BusinessException("Session not found", 404));
        if (!session.classId().equals(avatar.getClassId())) {
            throw new BusinessException("This avatar isn't enrolled in that class", 403);
        }

        ModerationService.ModerationResult mod =
                moderationService.screenInput(userId, avatarId, null, nickname, "en");
        if (mod.flagged() && mod.isHighSeverity()) {
            throw new BusinessException("That name isn't allowed — try a different name.", 422);
        }

        String participantToken = IdGenerator.newId();
        eventBus.registerParticipant(session.id(), participantToken, nickname);
        log.info("[Classroom] participant joined session={}", session.id());

        return new ClassroomJoinResponse(participantToken, nickname,
                toStateResponse(session, participantCount(session.id())));
    }

    public ClassroomAttackResponse attack(String sessionId, String userId, String avatarId,
                                           String participantToken, String questionId,
                                           Integer selectedIndex) {
        if (!eventBus.hasParticipant(sessionId, participantToken)) {
            throw new BusinessException("Not joined to this session", 403);
        }
        if (questionId == null || questionId.isBlank() || selectedIndex == null) {
            throw new BusinessException("questionId and selectedIndex are required", 400);
        }

        // Serialize per-session: two students racing the same live question
        // must not both land a hit on it.
        ReentrantLock lock = eventBus.lockFor(sessionId);
        lock.lock();
        try {
            ClassroomSession session = sessionRepository.findById(sessionId)
                    .orElseThrow(() -> new BusinessException("Session not found", 404));
            if (!ClassroomSession.STATUS_ACTIVE.equals(session.status())) {
                throw new BusinessException("This session isn't active", 400);
            }
            if (session.defeated()) {
                throw new BusinessException("This boss is already defeated", 400);
            }

            List<QuizQuestion> pool = fromJson(session.questionPoolJson());
            QuizQuestion expected = pool.get(session.currentIndex() % pool.size());
            if (!expected.id().equals(questionId)) {
                // Another participant already answered this question — not an
                // error, just stale: tell the caller to catch up to the current one.
                throw new BusinessException(
                        "Someone already answered this one — here's the next question", 409);
            }

            var key = answerKeyRepository.findByQuestionIds(Set.of(questionId)).get(questionId);
            boolean hitLanded = key != null && key.correctIndex() == selectedIndex;

            // Reuses the EXACT Phase-0 write path — the same quiz_question_results
            // + learning_event rows a solo quiz/boss attempt would produce, under
            // the student's REAL (private) userId/avatarId, never their nickname.
            submitQuizAnswersUseCase.recordSingleQuestionResult(
                    userId, avatarId, questionId, session.topicSlug(), hitLanded);

            ClassroomSession updated = sessionRepository.save(session.afterHit(hitLanded));

            String nickname = eventBus.nicknameFor(sessionId, participantToken);
            eventBus.publish(sessionId, "hit", hitEventJson(nickname, hitLanded,
                    updated.hpRemaining(), updated.hpMax()));

            if (updated.defeated()) {
                eventBus.publish(sessionId, "defeated", "{}");
                if (weaknessProfileService.isEnabled()) {
                    try {
                        weaknessProfileService.onMasteryUpdated(userId, avatarId);
                    } catch (Exception e) {
                        log.warn("[Classroom] mastery-update trigger failed (non-fatal): {}",
                                e.getMessage());
                    }
                }
            } else {
                QuizQuestion next = pool.get(updated.currentIndex() % pool.size());
                QuizQuestionResponse served =
                        quizService.serveGradable(updated.avatarId(), List.of(next)).get(0);
                eventBus.publish(sessionId, "question", questionEventJson(served));
            }

            return new ClassroomAttackResponse(
                    toStateResponse(updated, participantCount(sessionId)), hitLanded);
        } finally {
            lock.unlock();
        }
    }

    /** Live broadcast for one joined participant — chat's SSE wire format
     *  (event:/data: framing over a raw response writer) is reused verbatim
     *  by the controller for this; the multicast source itself is new
     *  plumbing (see {@link ClassroomEventBus}), since chat's own Flux is
     *  single-consumer by design and was never built for fan-out. */
    public Flux<ClassroomStreamEvent> stream(String sessionId, String participantToken) {
        if (!eventBus.hasParticipant(sessionId, participantToken)) {
            throw new BusinessException("Not joined to this session", 403);
        }
        return eventBus.streamFor(sessionId);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private ClassroomSession requireTeacherSession(String userId, String orgId, String sessionId) {
        centreAccessService.ensureStaff(userId, orgId);
        ClassroomSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException("Session not found", 404));
        // Confirms the session's class actually belongs to this org — throws
        // if not (mirrors ChallengeAdminController.requireClass).
        classCrudService.getClass(orgId, session.classId());
        return session;
    }

    private int participantCount(String sessionId) {
        return eventBus.participantCount(sessionId);
    }

    private ClassroomStateResponse toStateResponse(ClassroomSession session, int participantCount) {
        if (session.defeated() || ClassroomSession.STATUS_ENDED.equals(session.status())) {
            return new ClassroomStateResponse(session.id(), session.status(), session.topicSlug(),
                    session.joinCode(), session.hpRemaining(), session.hpMax(), session.defeated(),
                    participantCount, null);
        }
        List<QuizQuestion> pool = fromJson(session.questionPoolJson());
        QuizQuestion current = pool.get(session.currentIndex() % pool.size());
        QuizQuestionResponse served =
                quizService.serveGradable(session.avatarId(), List.of(current)).get(0);
        return new ClassroomStateResponse(session.id(), session.status(), session.topicSlug(),
                session.joinCode(), session.hpRemaining(), session.hpMax(), false, participantCount,
                served);
    }

    private String uniqueJoinCode() {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        for (int attempt = 0; attempt < 8; attempt++) {
            StringBuilder sb = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
            }
            String code = sb.toString();
            if (sessionRepository.findLiveByJoinCode(code).isEmpty()) return code;
        }
        throw new BusinessException("Could not generate a join code", 500);
    }

    private String hitEventJson(String nickname, boolean hitLanded, int hpRemaining, int hpMax) {
        try {
            return objectMapper.writeValueAsString(new java.util.LinkedHashMap<String, Object>() {{
                put("nickname", nickname);
                put("hitLanded", hitLanded);
                put("hpRemaining", hpRemaining);
                put("hpMax", hpMax);
            }});
        } catch (Exception e) {
            return "{}";
        }
    }

    private String questionEventJson(QuizQuestionResponse served) {
        try {
            return objectMapper.writeValueAsString(served);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String toJson(List<QuizQuestion> pool) {
        try {
            return objectMapper.writeValueAsString(pool);
        } catch (Exception e) {
            throw new BusinessException("Failed to persist question pool", 500);
        }
    }

    private List<QuizQuestion> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<QuizQuestion>>() {});
        } catch (Exception e) {
            throw new BusinessException("Corrupt question pool", 500);
        }
    }
}
