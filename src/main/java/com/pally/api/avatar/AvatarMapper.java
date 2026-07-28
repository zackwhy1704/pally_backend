package com.pally.api.avatar;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.api.avatar.dto.AvatarResponse;
import com.pally.domain.centre.dto.MochiConfig;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.ClassAvatarAppearance;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.infrastructure.persistence.organization.OrgClassJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgClassJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Maps {@link Avatar} domain objects to API response DTOs.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AvatarMapper {

    private final KnowledgeRepository knowledgeRepository;
    private final OrgClassJpaRepository orgClassRepository;
    private final ObjectMapper objectMapper;

    /**
     * Maps a single {@link Avatar} to an {@link AvatarResponse}.
     * Includes {@code fileCount} (number of READY knowledge files) so the
     * Flutter client can show "brain compiling…" when files exist but
     * wiki pages haven't been generated yet.
     *
     * <p>For a CENTRE_CLASS avatar this performs a single {@code findById} on the
     * linked org_class to resolve its Mochi config. Acceptable for the single-avatar
     * GET; for list responses use {@link #toResponseList(List)}, which batches the
     * class lookups to avoid N+1.
     */
    public AvatarResponse toResponse(Avatar avatar) {
        return toResponse(avatar, resolveMochiConfigForAvatar(avatar));
    }

    /// Resolves the Mochi config for a CENTRE_CLASS avatar — whether it's a
    /// student's class-bound avatar (by classId) OR the hidden class corpus
    /// (classId == null, so look it up by corpusAvatarId == avatar.id). Without
    /// the corpus branch the centre owner's own view rendered the plain default.
    private MochiConfig resolveMochiConfigForAvatar(Avatar avatar) {
        if (!avatar.isCentreClass()) return null;
        if (avatar.getClassId() != null && !avatar.getClassId().isBlank()) {
            return resolveMochiConfig(avatar.getClassId());
        }
        return orgClassRepository.findByCorpusAvatarId(avatar.getId())
                .map(cls -> deserialize(cls.getMochiConfig()))
                .orElse(null);
    }

    /**
     * Maps a list of {@link Avatar} objects to a list of {@link AvatarResponse} DTOs.
     *
     * <p>Mochi configs for CENTRE_CLASS avatars are resolved in a single batched
     * {@code findAllById} over the distinct class ids, so the list endpoint never
     * issues one org_class query per avatar (no N+1).
     */
    public List<AvatarResponse> toResponseList(List<Avatar> avatars) {
        // Student class avatars resolve by classId; corpus avatars (classId null)
        // resolve by corpusAvatarId == avatar.id. Both batched to avoid N+1.
        Set<String> classIds = avatars.stream()
                .filter(Avatar::isCentreClass)
                .map(Avatar::getClassId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        Set<String> corpusIds = avatars.stream()
                .filter(a -> a.isCentreClass()
                        && (a.getClassId() == null || a.getClassId().isBlank()))
                .map(Avatar::getId)
                .collect(Collectors.toSet());

        Map<String, MochiConfig> byClassId = new HashMap<>();
        if (!classIds.isEmpty()) {
            for (OrgClassJpaEntity cls : orgClassRepository.findAllById(classIds)) {
                byClassId.put(cls.getId(), deserialize(cls.getMochiConfig()));
            }
        }
        Map<String, MochiConfig> byCorpusAvatarId = new HashMap<>();
        if (!corpusIds.isEmpty()) {
            for (OrgClassJpaEntity cls : orgClassRepository.findByCorpusAvatarIdIn(corpusIds)) {
                byCorpusAvatarId.put(cls.getCorpusAvatarId(), deserialize(cls.getMochiConfig()));
            }
        }

        return avatars.stream()
                .map(a -> toResponse(
                        a,
                        !a.isCentreClass()
                                ? null
                                : (a.getClassId() != null && !a.getClassId().isBlank()
                                        ? byClassId.get(a.getClassId())
                                        : byCorpusAvatarId.get(a.getId()))))
                .toList();
    }

    /** Shared body — builds the DTO given a pre-resolved Mochi config (may be null). */
    private AvatarResponse toResponse(Avatar avatar, MochiConfig mochiConfig) {
        List<KnowledgeFile> files = knowledgeRepository.findByAvatarId(avatar.getId());
        int fileCount = (int) files.stream()
                .filter(f -> f.getStatus() == KnowledgeFile.Status.READY
                          || f.getStatus() == KnowledgeFile.Status.PROCESSING)
                .count();

        // ── Segmented-compile honesty: derive the additive client surface from LIVE file
        // states (nothing persisted, no schema change, brainState untouched). ────────────
        boolean brainReady = avatar.getBrainState() == Avatar.BrainState.READY;
        boolean anyReadyFile = files.stream().anyMatch(f -> f.getStatus() == KnowledgeFile.Status.READY);
        long pendingChunks = files.stream()
                .filter(f -> f.getStatus() == KnowledgeFile.Status.PENDING_CHUNK).count();
        long segmented = files.stream()
                .filter(f -> f.getStatus() == KnowledgeFile.Status.SEGMENTED).count();
        // Awaiting the user to pick chapters: non-READY brain, no ready files, but
        // compile-time segmentation left SEGMENTED/PENDING_CHUNK files to choose from.
        boolean awaitingChapterSelection =
                !brainReady && !anyReadyFile && (pendingChunks + segmented) > 0;
        int pendingChapterCount = (int) pendingChunks;
        // Honest failure: non-READY brain, no ready files, every file FAILED/IRRELEVANT, no pages.
        // FAILED (extraction couldn't read the file) and IRRELEVANT (extraction worked, but the
        // content doesn't match the class subject) are DIFFERENT causes and must not share one
        // sentence — a subject-mismatched book must not be told "we couldn't read enough text".
        boolean allFailedOrIrrelevant = !files.isEmpty() && files.stream().allMatch(
                f -> f.getStatus() == KnowledgeFile.Status.FAILED
                  || f.getStatus() == KnowledgeFile.Status.IRRELEVANT);
        String compileFailureReason = null;
        String compileFailureKind = null;
        if (!brainReady && !anyReadyFile && allFailedOrIrrelevant && avatar.getWikiPageCount() == 0) {
            boolean anyFailed = files.stream().anyMatch(f -> f.getStatus() == KnowledgeFile.Status.FAILED);
            boolean anyIrrelevant = files.stream().anyMatch(f -> f.getStatus() == KnowledgeFile.Status.IRRELEVANT);
            if (anyFailed && anyIrrelevant) {
                // Mixed batch: name both causes honestly rather than picking one blanket.
                compileFailureReason = "Some files couldn't be read (try a clearer scan or a text-based PDF), "
                        + "and others don't match your class's subject — check they're the right material.";
                compileFailureKind = "MIXED";
            } else if (anyIrrelevant) {
                compileFailureReason = "This file doesn't seem to match your class's subject — "
                        + "double check it's the right material, or upload something else.";
                compileFailureKind = "IRRELEVANT";
            } else {
                compileFailureReason = "We couldn't read enough text from this file — "
                        + "try a clearer scan or a text-based PDF.";
                compileFailureKind = "FAILED";
            }
        }

        // Class avatars wear a server-derived "uniform" — band colour, subject
        // glyph, initials — computed deterministically. PERSONAL avatars carry
        // no appearance (null → omitted from JSON).
        ClassAvatarAppearance appearance = avatar.isCentreClass()
                ? ClassAvatarAppearance.derive(
                        avatar.getClassId(), avatar.getSubject(),
                        avatar.getCentreBrandName() != null && !avatar.getCentreBrandName().isBlank()
                                ? avatar.getCentreBrandName() : avatar.getName())
                : null;

        return new AvatarResponse(
                avatar.getId(),
                avatar.getName(),
                avatar.getSubject(),
                avatar.getCharacterType(),
                avatar.getWikiPageCount(),
                fileCount,
                avatar.getCreatedAt(),
                avatar.getGradeLevel(),
                avatar.getCurriculumType(),
                avatar.getPedagogyMode(),
                avatar.getTestDate(),
                avatar.getBrainState().name(),
                avatar.isActive(),
                avatar.getTeacherPreferences(),
                avatar.isCentreAvatar(),
                avatar.isAvatarLocked(),
                avatar.getCentreBrandName(),
                avatar.getCentreAccentColor(),
                avatar.getCosmeticEyewear(),
                avatar.getCosmeticClothes(),
                avatar.getCosmeticShoes(),
                avatar.getKind().name(),
                appearance,
                mochiConfig,
                // Only a student's class-bound avatar exposes its classId (used by
                // the mobile leave-class action); the corpus has classId == null.
                avatar.isCentreClass() ? avatar.getClassId() : null,
                awaitingChapterSelection,
                pendingChapterCount,
                compileFailureReason,
                compileFailureKind,
                avatar.getContentLanguage()
        );
    }

    /** Resolves the Mochi config for a single class id via {@code findById}; null if absent/unset. */
    private MochiConfig resolveMochiConfig(String classId) {
        if (classId == null || classId.isBlank()) return null;
        return orgClassRepository.findById(classId)
                .map(cls -> deserialize(cls.getMochiConfig()))
                .orElse(null);
    }

    /** Deserialize the stored TEXT blob to a MochiConfig; null when unset/corrupt. */
    private MochiConfig deserialize(String blob) {
        if (blob == null || blob.isBlank()) return null;
        try {
            return objectMapper.readValue(blob, MochiConfig.class);
        } catch (JsonProcessingException e) {
            log.warn("[AvatarMapper] could not deserialize mochi_config blob: {}", e.getMessage());
            return null;
        }
    }
}
