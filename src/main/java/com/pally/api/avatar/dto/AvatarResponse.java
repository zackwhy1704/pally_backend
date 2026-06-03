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
        /// Number of READY knowledge files — included so the frontend can
        /// distinguish "files uploaded, brain still compiling" from
        /// "no files yet". The Flutter library screen uses this to show
        /// a "compiling…" badge instead of "no notes yet".
        int fileCount,
        Instant createdAt,
        String gradeLevel,
        String curriculumType,
        Avatar.PedagogyMode pedagogyMode,
        LocalDate testDate,
        /// Brain compilation state: READY | PENDING_RECOMPILE | COMPILING
        String brainState,
        /// False when this avatar is outside the user's active slot cap
        /// (trial expired or free-tier limit reached). Inactive avatars
        /// are visible but chat/quiz/upload are blocked until re-selected.
        boolean isActive
) {}
