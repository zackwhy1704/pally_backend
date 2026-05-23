package com.pally.api.avatar.dto;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;

import java.time.Instant;
import java.time.LocalDate;

public record AvatarResponse(
        String id,
        String name,
        Subject subject,
        CharacterType characterType,
        int wikiPageCount,
        Instant createdAt,
        String gradeLevel,
        String curriculumType,
        Avatar.PedagogyMode pedagogyMode,
        LocalDate testDate
) {}
