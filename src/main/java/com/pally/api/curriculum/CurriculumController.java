package com.pally.api.curriculum;

import com.pally.infrastructure.ai.ClaudeSyllabusParser;
import com.pally.infrastructure.ocr.PdfTextExtractor;
import com.pally.infrastructure.persistence.avatar.AvatarJpaRepository;
import com.pally.infrastructure.persistence.curriculum.CurriculumJpaEntity;
import com.pally.infrastructure.persistence.curriculum.CurriculumJpaRepository;
import com.pally.infrastructure.persistence.curriculum.CurriculumTopicJpaEntity;
import com.pally.infrastructure.persistence.curriculum.CurriculumTopicJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Curriculum spine endpoints. A curriculum is an ordered topic list a
 * tutor's wiki/quiz journey can be measured against (the audit's Gap C).
 *
 * <p>v1 surfaces:
 *  - List curricula visible to the caller (built-in shared + own uploads).
 *  - List topics inside a curriculum.
 *  - Upload a syllabus PDF/text → Claude parses → persists as a new curriculum.
 *
 * <p>Auto-mapping wiki pages to topics is a separate follow-up; for now the
 * topic list itself is what drives the syllabus-coverage UX.
 */
@RestController
@RequestMapping("/api/v1/curricula")
@RequiredArgsConstructor
@Slf4j
public class CurriculumController {

    private final CurriculumJpaRepository curriculumRepo;
    private final CurriculumTopicJpaRepository topicRepo;
    private final ClaudeSyllabusParser parser;
    private final PdfTextExtractor pdfExtractor;
    private final AvatarJpaRepository avatarJpaRepo;

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @AuthenticationPrincipal String userId,
            @RequestParam(required = false) String subject) {
        List<CurriculumJpaEntity> rows =
                curriculumRepo.findVisibleToUser(userId, subject);
        List<Map<String, Object>> dtos = rows.stream()
                .map(this::toSummary)
                .toList();
        return ResponseEntity.ok(
                ApiResponse.success(Map.of("curricula", dtos)));
    }

    @GetMapping("/{curriculumId}/topics")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> topics(
            @AuthenticationPrincipal String userId,
            @PathVariable String curriculumId) {
        CurriculumJpaEntity c = curriculumRepo.findById(curriculumId)
                .orElseThrow(() ->
                        new BusinessException("Curriculum not found", 404));
        if (c.getOwnerUserId() != null
                && !c.getOwnerUserId().equals(userId)) {
            throw new BusinessException("Forbidden", 403);
        }
        List<Map<String, Object>> topics = topicRepo
                .findByCurriculumIdOrderBySequenceAsc(curriculumId).stream()
                .map(t -> Map.<String, Object>of(
                        "id", t.getId(),
                        "name", t.getName(),
                        "slug", t.getSlug(),
                        "sequence", t.getSequence()))
                .toList();
        Map<String, Object> body = new HashMap<>(toSummary(c));
        body.put("topics", topics);
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    /// Upload a syllabus document — PDF (preferred) or plain text via the
    /// {@code text} form field. Claude extracts an ordered topic list and we
    /// persist it as a new curriculum owned by the uploader.
    @PostMapping("/upload")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> upload(
            @AuthenticationPrincipal String userId,
            @RequestParam(required = false) MultipartFile file,
            @RequestParam(required = false) String text,
            @RequestParam String subject,
            @RequestParam(required = false) String grade,
            @RequestParam(required = false) String name) {
        if (subject == null || subject.isBlank()) {
            throw new BusinessException("subject is required", 400);
        }
        String raw = text;
        if ((raw == null || raw.isBlank()) && file != null && !file.isEmpty()) {
            try {
                raw = pdfExtractor.extract(file.getInputStream()).text();
            } catch (Exception e) {
                log.warn("[Syllabus] PDF extract failed: {}", e.getMessage());
                throw new BusinessException(
                        "Could not read the syllabus PDF", 400);
            }
        }
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(
                    "Provide either a syllabus file or text body", 400);
        }

        List<ClaudeSyllabusParser.ParsedTopic> topics =
                parser.parse(subject, raw);
        if (topics.isEmpty()) {
            throw new BusinessException(
                    "Could not detect any topics in that syllabus", 422);
        }

        Instant now = Instant.now();
        CurriculumJpaEntity c = new CurriculumJpaEntity();
        c.setId(IdGenerator.newId());
        c.setName(name != null && !name.isBlank()
                ? name
                : subject + (grade != null ? " · " + grade : "") + " syllabus");
        c.setSubject(subject.toUpperCase());
        c.setGrade(grade);
        c.setOwnerUserId(userId);
        c.setDefault(false);
        c.setCreatedAt(now);
        curriculumRepo.save(c);

        int seq = 0;
        for (var pt : topics) {
            CurriculumTopicJpaEntity t = new CurriculumTopicJpaEntity();
            t.setId(IdGenerator.newId());
            t.setCurriculumId(c.getId());
            t.setName(pt.name());
            t.setSlug(pt.slug());
            t.setSequence(seq++);
            t.setCreatedAt(now);
            topicRepo.save(t);
        }
        log.info("[Curriculum] user={} created curriculum={} topics={}",
                userId, c.getId(), topics.size());

        Map<String, Object> body = new HashMap<>(toSummary(c));
        body.put("topicCount", topics.size());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(body));
    }

    /// Attach a curriculum to an avatar so subsequent quiz / coverage UX
    /// can measure against the syllabus. Pass curriculumId = null in the
    /// JSON body to detach (handled by treating absent / blank as null).
    @PostMapping("/avatars/{avatarId}/attach")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> attachToAvatar(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @org.springframework.web.bind.annotation.RequestBody
                    Map<String, String> body) {
        var avatar = avatarJpaRepo.findById(avatarId)
                .orElseThrow(() ->
                        new BusinessException("Avatar not found", 404));
        if (!userId.equals(avatar.getUserId())) {
            throw new BusinessException("Forbidden", 403);
        }
        String curriculumId = body.get("curriculumId");
        if (curriculumId != null && !curriculumId.isBlank()) {
            CurriculumJpaEntity c = curriculumRepo.findById(curriculumId)
                    .orElseThrow(() -> new BusinessException(
                            "Curriculum not found", 404));
            if (c.getOwnerUserId() != null
                    && !c.getOwnerUserId().equals(userId)) {
                throw new BusinessException("Forbidden", 403);
            }
            avatar.setCurriculumId(curriculumId);
        } else {
            avatar.setCurriculumId(null);
        }
        avatarJpaRepo.save(avatar);
        log.info("[Curriculum] avatar={} curriculum={}",
                avatarId, avatar.getCurriculumId());
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "avatarId", avatarId,
                "curriculumId", avatar.getCurriculumId() == null
                        ? "" : avatar.getCurriculumId())));
    }

    private Map<String, Object> toSummary(CurriculumJpaEntity c) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("subject", c.getSubject());
        m.put("grade", c.getGrade());
        m.put("ownerUserId", c.getOwnerUserId());
        m.put("isDefault", c.isDefault());
        m.put("createdAt", c.getCreatedAt().toString());
        return m;
    }
}
