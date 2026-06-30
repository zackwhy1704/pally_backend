package com.pally.infrastructure.persistence.homework;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.homework.HomeworkSubmission;
import com.pally.domain.homework.HomeworkSubmissionRepository;
import com.pally.domain.homework.HomeworkSubmissionStatus;
import com.pally.domain.homework.SubmissionFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Maps between the {@link HomeworkSubmission} domain type and its JPA row,
 * including the artifact list ⇄ JSON. Mirrors the clean Wiki port+adapter
 * pattern — JPA entities never leave this class.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HomeworkSubmissionRepositoryAdapter implements HomeworkSubmissionRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<SubmissionFile>> FILE_LIST = new TypeReference<>() {};

    private final HomeworkSubmissionJpaRepository jpa;

    @Override
    @Transactional
    public HomeworkSubmission save(HomeworkSubmission submission) {
        return toDomain(jpa.save(toEntity(submission)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<HomeworkSubmission> findById(String id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<HomeworkSubmission> findByClassId(String classId) {
        return jpa.findByClassIdOrderByCreatedAtDesc(classId).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<HomeworkSubmission> findByClassIdAndStatus(String classId,
                                                           HomeworkSubmissionStatus status) {
        return jpa.findByClassIdAndStatusOrderByCreatedAtDesc(classId, status)
                .stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<HomeworkSubmission> findByStudentIdAndClassId(String studentId, String classId) {
        return jpa.findByStudentIdAndClassIdOrderByCreatedAtDesc(studentId, classId)
                .stream().map(this::toDomain).toList();
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    private HomeworkSubmissionJpaEntity toEntity(HomeworkSubmission s) {
        HomeworkSubmissionJpaEntity e = new HomeworkSubmissionJpaEntity();
        e.setId(s.getId());
        e.setClassId(s.getClassId());
        e.setStudentId(s.getStudentId());
        e.setTitle(s.getTitle());
        e.setSubject(s.getSubject());
        e.setFilesJson(writeFiles(s.getFiles()));
        e.setExtractedText(s.getExtractedText());
        e.setStatus(s.getStatus());
        e.setAiDraftFeedbackJson(s.getAiDraftFeedbackJson());
        e.setAiDraftedAt(s.getAiDraftedAt());
        e.setTeacherFeedback(s.getTeacherFeedback());
        e.setTeacherGrade(s.getTeacherGrade());
        e.setReleasedAt(s.getReleasedAt());
        e.setCreatedAt(s.getCreatedAt());
        e.setUpdatedAt(s.getUpdatedAt());
        return e;
    }

    private HomeworkSubmission toDomain(HomeworkSubmissionJpaEntity e) {
        return HomeworkSubmission.reconstitute(
                e.getId(), e.getClassId(), e.getStudentId(), e.getTitle(), e.getSubject(),
                readFiles(e.getFilesJson()), e.getExtractedText(), e.getStatus(),
                e.getAiDraftFeedbackJson(), e.getAiDraftedAt(), e.getTeacherFeedback(),
                e.getTeacherGrade(), e.getReleasedAt(), e.getCreatedAt(), e.getUpdatedAt());
    }

    private String writeFiles(List<SubmissionFile> files) {
        try {
            return MAPPER.writeValueAsString(files == null ? List.of() : files);
        } catch (Exception ex) {
            log.error("[Homework] failed to serialize files", ex);
            return "[]";
        }
    }

    private List<SubmissionFile> readFiles(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, FILE_LIST);
        } catch (Exception ex) {
            log.error("[Homework] failed to parse files_json: {}", json, ex);
            return List.of();
        }
    }
}
