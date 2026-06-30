package com.pally.infrastructure.persistence.marking;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.marking.MarkingReference;
import com.pally.domain.marking.MarkingReferenceFile;
import com.pally.domain.marking.MarkingReferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Maps between the {@link MarkingReference} domain type and its JPA row,
 * including the artifact list ⇄ JSON. Mirrors the homework adapter — JPA
 * entities never leave this class.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MarkingReferenceRepositoryAdapter implements MarkingReferenceRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<MarkingReferenceFile>> FILE_LIST = new TypeReference<>() {};

    private final MarkingReferenceJpaRepository jpa;

    @Override
    @Transactional
    public MarkingReference save(MarkingReference reference) {
        return toDomain(jpa.save(toEntity(reference)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MarkingReference> findById(String id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MarkingReference> findByClassId(String classId) {
        return jpa.findByClassIdOrderByCreatedAtDesc(classId).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional
    public void deleteById(String id) {
        jpa.deleteById(id);
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    private MarkingReferenceJpaEntity toEntity(MarkingReference r) {
        MarkingReferenceJpaEntity e = new MarkingReferenceJpaEntity();
        e.setId(r.getId());
        e.setClassId(r.getClassId());
        e.setKind(r.getKind());
        e.setTitle(r.getTitle());
        e.setNote(r.getNote());
        e.setFilesJson(writeFiles(r.getFiles()));
        e.setExtractedText(r.getExtractedText());
        e.setCreatedAt(r.getCreatedAt());
        e.setUpdatedAt(r.getUpdatedAt());
        return e;
    }

    private MarkingReference toDomain(MarkingReferenceJpaEntity e) {
        return MarkingReference.reconstitute(
                e.getId(), e.getClassId(), e.getKind(), e.getTitle(), e.getNote(),
                readFiles(e.getFilesJson()), e.getExtractedText(),
                e.getCreatedAt(), e.getUpdatedAt());
    }

    private String writeFiles(List<MarkingReferenceFile> files) {
        try {
            return MAPPER.writeValueAsString(files == null ? List.of() : files);
        } catch (Exception ex) {
            log.error("[Marking] failed to serialize files", ex);
            return "[]";
        }
    }

    private List<MarkingReferenceFile> readFiles(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, FILE_LIST);
        } catch (Exception ex) {
            log.error("[Marking] failed to parse files_json: {}", json, ex);
            return List.of();
        }
    }
}
