package com.pally.domain.challenge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.infrastructure.persistence.challenge.ChallengeAnswerJpaEntity;
import com.pally.infrastructure.persistence.challenge.ChallengeAnswerJpaRepository;
import com.pally.infrastructure.persistence.challenge.ClassChallengeJpaEntity;
import com.pally.infrastructure.persistence.challenge.ClassChallengeJpaRepository;
import com.pally.infrastructure.push.FcmService;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Class challenge questions with locked answers and a timed reveal. Before
 * {@code revealAt}, the correct answer and answer distribution are server-
 * withheld; students can only see the question (and options for MCQ) plus
 * whether they themselves answered. Answers are immutable once submitted.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChallengeService {

    private final ClassChallengeJpaRepository challengeRepo;
    private final ChallengeAnswerJpaRepository answerRepo;
    private final FcmService fcmService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── Teacher: create ───────────────────────────────────────────────────

    @Transactional
    public ClassChallengeJpaEntity create(String classId, String question,
                                          List<String> options, String answer,
                                          Instant revealAt, String createdBy) {
        if (question == null || question.isBlank()) {
            throw new BusinessException("question is required", 400);
        }
        if (answer == null || answer.isBlank()) {
            throw new BusinessException("answer is required", 400);
        }
        if (revealAt == null) {
            throw new BusinessException("revealAt is required", 400);
        }
        ClassChallengeJpaEntity c = new ClassChallengeJpaEntity();
        c.setId(IdGenerator.newId());
        c.setClassId(classId);
        c.setQuestion(question);
        c.setOptions(serializeOptions(options));
        c.setAnswer(answer);
        c.setRevealAt(revealAt);
        c.setCreatedBy(createdBy);
        c.setCreatedAt(Instant.now());
        challengeRepo.save(c);
        log.info("[Challenge] created challenge={} class={} revealAt={}", c.getId(), classId, revealAt);
        return c;
    }

    // ── Student: answer (locked) ──────────────────────────────────────────

    @Transactional
    public Map<String, Object> answer(String challengeId, String userId, String answer) {
        ClassChallengeJpaEntity c = challengeRepo.findById(challengeId)
                .orElseThrow(() -> new BusinessException("Challenge not found", 404));
        if (answer == null || answer.isBlank()) {
            throw new BusinessException("answer is required", 400);
        }
        // Locked: no edits. 409 if already answered.
        if (answerRepo.existsByChallengeIdAndUserId(challengeId, userId)) {
            throw new BusinessException("You already answered this challenge", 409);
        }
        ChallengeAnswerJpaEntity a = new ChallengeAnswerJpaEntity();
        a.setId(IdGenerator.newId());
        a.setChallengeId(challengeId);
        a.setUserId(userId);
        a.setAnswer(answer);
        a.setCreatedAt(Instant.now());
        answerRepo.save(a);
        log.info("[Challenge] answer locked challenge={} (user dedup only)", challengeId);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("challengeId", challengeId);
        resp.put("answered", true);
        // Correctness is NEVER returned before reveal.
        if (c.revealed()) {
            resp.put("correct", c.getAnswer().equalsIgnoreCase(answer));
        }
        return resp;
    }

    // ── Get (gated by revealAt) ───────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> get(String challengeId, String userId) {
        ClassChallengeJpaEntity c = challengeRepo.findById(challengeId)
                .orElseThrow(() -> new BusinessException("Challenge not found", 404));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("classId", c.getClassId());
        m.put("question", c.getQuestion());
        m.put("options", deserializeOptions(c.getOptions()));
        m.put("revealAt", c.getRevealAt().toString());
        m.put("revealed", c.revealed());
        m.put("answered", answerRepo.existsByChallengeIdAndUserId(challengeId, userId));

        if (c.revealed()) {
            // Post-reveal: expose the correct answer + answer distribution.
            m.put("answer", c.getAnswer());
            m.put("distribution", distribution(challengeId));
            answerRepo.findByChallengeIdAndUserId(challengeId, userId).ifPresent(a ->
                    m.put("correct", c.getAnswer().equalsIgnoreCase(a.getAnswer())));
        }
        // Before reveal: NO answer, NO correctness, NO distribution.
        return m;
    }

    // ── List for class feed ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listForClass(String classId, String userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ClassChallengeJpaEntity c : challengeRepo.findByClassIdOrderByCreatedAtDesc(classId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("question", c.getQuestion());
            m.put("options", deserializeOptions(c.getOptions()));
            m.put("revealAt", c.getRevealAt().toString());
            m.put("revealed", c.revealed());
            m.put("answered", answerRepo.existsByChallengeIdAndUserId(c.getId(), userId));
            if (c.revealed()) {
                m.put("answer", c.getAnswer());
            }
            out.add(m);
        }
        return out;
    }

    /** Answer distribution: [{answer, count}], no user ids. */
    private List<Map<String, Object>> distribution(String challengeId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] row : answerRepo.distributionByChallenge(challengeId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("answer", row[0]);
            m.put("count", ((Number) row[1]).longValue());
            out.add(m);
        }
        return out;
    }

    // ── Scheduled reveal sweep: notify only students who answered ──────────

    @Scheduled(fixedDelayString = "${pally.challenge.reveal-sweep-ms:60000}")
    @Transactional
    public void revealSweep() {
        List<ClassChallengeJpaEntity> due = challengeRepo.findDueForReveal(Instant.now());
        for (ClassChallengeJpaEntity c : due) {
            notifyAnswerers(c);
            c.setNotified(true);
            challengeRepo.save(c);
        }
        if (!due.isEmpty()) {
            log.info("[Challenge] reveal sweep notified {} challenge(s)", due.size());
        }
    }

    private void notifyAnswerers(ClassChallengeJpaEntity c) {
        for (ChallengeAnswerJpaEntity a : answerRepo.findByChallengeId(c.getId())) {
            boolean correct = c.getAnswer().equalsIgnoreCase(a.getAnswer());
            String title = "Challenge answer revealed!";
            String body = correct ? "You nailed it 🎉 Tap to see how the class did."
                    : "See the answer and how the class did.";
            fcmService.sendToUser(a.getUserId(), title, body);
        }
    }

    // ── JSON helpers ──────────────────────────────────────────────────────

    private String serializeOptions(List<String> options) {
        if (options == null || options.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(options);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> deserializeOptions(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return null;
        }
    }
}
