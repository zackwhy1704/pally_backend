package com.pally.api.avatar;

import com.pally.api.avatar.dto.AvatarResponse;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.ClassAvatarAppearance;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps {@link Avatar} domain objects to API response DTOs.
 */
@Component
@RequiredArgsConstructor
public class AvatarMapper {

    private final KnowledgeRepository knowledgeRepository;

    /**
     * Maps a single {@link Avatar} to an {@link AvatarResponse}.
     * Includes {@code fileCount} (number of READY knowledge files) so the
     * Flutter client can show "brain compiling…" when files exist but
     * wiki pages haven't been generated yet.
     */
    public AvatarResponse toResponse(Avatar avatar) {
        int fileCount = (int) knowledgeRepository.findByAvatarId(avatar.getId())
                .stream()
                .filter(f -> f.getStatus() == KnowledgeFile.Status.READY
                          || f.getStatus() == KnowledgeFile.Status.PROCESSING)
                .count();

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
                appearance
        );
    }

    /**
     * Maps a list of {@link Avatar} objects to a list of {@link AvatarResponse} DTOs.
     */
    public List<AvatarResponse> toResponseList(List<Avatar> avatars) {
        return avatars.stream().map(this::toResponse).toList();
    }
}
