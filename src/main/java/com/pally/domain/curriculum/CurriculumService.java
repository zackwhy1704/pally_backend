package com.pally.domain.curriculum;

import com.pally.domain.curriculum.port.SyllabusParserPort;
import com.pally.domain.curriculum.port.SyllabusPdfPort;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Domain service for the curriculum spine (list, topic drill-down, upload, avatar attach/detach).
 * Zero imports from {@code infrastructure.*} or {@code jakarta.persistence.*}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CurriculumService {

    private final CurriculumRepository curriculumRepository;
    private final CurriculumTopicRepository topicRepository;
    private final AvatarCurriculumPort avatarCurriculumPort;
    private final SyllabusPdfPort pdfPort;
    private final SyllabusParserPort parserPort;

    // ── List ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> listCurricula(String userId, String subject) {
        List<Curriculum> rows = curriculumRepository.findVisibleToUser(userId, subject);
        List<Map<String, Object>> dtos = rows.stream().map(this::toSummary).toList();
        return Map.of("curricula", dtos);
    }

    // ── Topics ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> getTopics(String userId, String curriculumId) {
        Curriculum c = curriculumRepository.findById(curriculumId)
                .orElseThrow(() -> new BusinessException("Curriculum not found", 404));
        if (c.ownerUserId() != null && !c.ownerUserId().equals(userId)) {
            throw new BusinessException("Forbidden", 403);
        }
        List<Map<String, Object>> topics = topicRepository
                .findByCurriculumIdOrderBySequenceAsc(curriculumId).stream()
                .map(t -> Map.<String, Object>of(
                        "id", t.id(),
                        "name", t.name(),
                        "slug", t.slug(),
                        "sequence", t.sequence()))
                .toList();
        Map<String, Object> body = new HashMap<>(toSummary(c));
        body.put("topics", topics);
        return body;
    }

    // ── Upload ──────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> uploadSyllabus(
            String userId,
            InputStream pdfStream,
            String rawText,
            String subject,
            String grade,
            String name) {
        if (subject == null || subject.isBlank()) {
            throw new BusinessException("subject is required", 400);
        }
        String text = rawText;
        if ((text == null || text.isBlank()) && pdfStream != null) {
            try {
                text = pdfPort.extractText(pdfStream);
            } catch (Exception e) {
                log.warn("[Syllabus] PDF extract failed: {}", e.getMessage());
                throw new BusinessException("Could not read the syllabus PDF", 400);
            }
        }
        if (text == null || text.isBlank()) {
            throw new BusinessException("Provide either a syllabus file or text body", 400);
        }

        List<ParsedTopic> topics = parserPort.parse(subject, text);
        if (topics.isEmpty()) {
            throw new BusinessException("Could not detect any topics in that syllabus", 422);
        }

        Instant now = Instant.now();
        Curriculum curriculum = new Curriculum(
                IdGenerator.newId(),
                name != null && !name.isBlank()
                        ? name
                        : subject + (grade != null ? " · " + grade : "") + " syllabus",
                subject.toUpperCase(),
                grade,
                userId,
                false,
                now);
        curriculumRepository.save(curriculum);

        int seq = 0;
        List<CurriculumTopic> saved = new ArrayList<>();
        for (ParsedTopic pt : topics) {
            CurriculumTopic topic = new CurriculumTopic(
                    IdGenerator.newId(),
                    curriculum.id(),
                    pt.name(),
                    pt.slug(),
                    seq++,
                    now);
            topicRepository.save(topic);
            saved.add(topic);
        }
        log.info("[Curriculum] user={} created curriculum={} topics={}",
                userId, curriculum.id(), saved.size());

        Map<String, Object> body = new HashMap<>(toSummary(curriculum));
        body.put("topicCount", saved.size());
        return body;
    }

    // ── Attach / detach avatar ───────────────────────────────────────────────

    @Transactional
    public Map<String, Object> attachToAvatar(String userId, String avatarId, String curriculumId) {
        String ownerUserId = avatarCurriculumPort.findOwnerUserId(avatarId)
                .orElseThrow(() -> new BusinessException("Avatar not found", 404));
        if (!userId.equals(ownerUserId)) {
            throw new BusinessException("Forbidden", 403);
        }
        if (curriculumId != null && !curriculumId.isBlank()) {
            Curriculum c = curriculumRepository.findById(curriculumId)
                    .orElseThrow(() -> new BusinessException("Curriculum not found", 404));
            if (c.ownerUserId() != null && !c.ownerUserId().equals(userId)) {
                throw new BusinessException("Forbidden", 403);
            }
        }
        String stored = avatarCurriculumPort.attachCurriculum(
                avatarId,
                (curriculumId != null && !curriculumId.isBlank()) ? curriculumId : null);
        log.info("[Curriculum] avatar={} curriculum={}", avatarId, stored);
        return Map.of(
                "avatarId", avatarId,
                "curriculumId", stored == null ? "" : stored);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Map<String, Object> toSummary(Curriculum c) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", c.id());
        m.put("name", c.name());
        m.put("subject", c.subject());
        m.put("grade", c.grade());
        m.put("ownerUserId", c.ownerUserId());
        m.put("isDefault", c.isDefault());
        m.put("createdAt", c.createdAt().toString());
        return m;
    }

    /** Domain record mirroring {@code ClaudeSyllabusParser.ParsedTopic}. */
    public record ParsedTopic(String name, String slug) {}
}
