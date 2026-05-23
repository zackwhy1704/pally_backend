package com.pally.api.avatar.dto;

import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateAvatarRequest(
        @NotBlank(message = "Avatar name must not be blank") String name,
        @NotNull(message = "Subject is required") Subject subject,
        @NotNull(message = "Character type is required") CharacterType characterType,
        String gradeLevel,
        String curriculumType
) {}
