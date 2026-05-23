package com.pally.api.avatar;

import com.pally.api.avatar.dto.AvatarResponse;
import com.pally.domain.avatar.Avatar;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps {@link Avatar} domain objects to API response DTOs.
 */
@Component
public class AvatarMapper {

    /**
     * Maps a single {@link Avatar} to an {@link AvatarResponse}.
     *
     * @param avatar domain avatar
     * @return response DTO
     */
    public AvatarResponse toResponse(Avatar avatar) {
        return new AvatarResponse(
                avatar.getId(),
                avatar.getName(),
                avatar.getSubject(),
                avatar.getCharacterType(),
                avatar.getWikiPageCount(),
                avatar.getCreatedAt(),
                avatar.getGradeLevel(),
                avatar.getCurriculumType(),
                avatar.getPedagogyMode(),
                avatar.getTestDate()
        );
    }

    /**
     * Maps a list of {@link Avatar} objects to a list of {@link AvatarResponse} DTOs.
     *
     * @param avatars list of domain avatars
     * @return list of response DTOs
     */
    public List<AvatarResponse> toResponseList(List<Avatar> avatars) {
        return avatars.stream().map(this::toResponse).toList();
    }
}
