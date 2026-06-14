package com.pally.api.avatar.dto;

import com.pally.api.centre.dto.MochiConfig;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.ClassAvatarAppearance;
import com.pally.domain.avatar.Subject;

import com.fasterxml.jackson.annotation.JsonInclude;

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
        boolean isActive,
        /// Optional teacher-specified method preferences (max 500 chars).
        /// Injected into Block 2 of the system prompt.
        String teacherPreferences,
        // ── Centre-mode fields (V59) — null/false for personal avatars ───────
        /// True when this avatar is a centre-provisioned, closed-book Mochi.
        boolean centreManaged,
        /// True when centre access is paused (lapse/leave) — chat blocked.
        boolean avatarLocked,
        /// Display name override + accent colour from the class config.
        String centreBrandName,
        String centreAccentColor,
        /// Cosmetic accessory slot ids — null until layered art exists.
        String cosmeticEyewear,
        String cosmeticClothes,
        String cosmeticShoes,
        // ── Avatar kind split (V65) ──────────────────────────────────────────
        /// "PERSONAL" (student collectible) or "CENTRE_CLASS" (class-bound).
        /// Clients group avatars by this so class avatars never sit in the
        /// collection grid. Always present.
        String kind,
        /// Server-derived class "uniform" appearance. Present ONLY for
        /// CENTRE_CLASS avatars; null/absent for PERSONAL. Clients render it
        /// verbatim (band colour + subject-glyph icon + initials).
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ClassAvatarAppearance appearance,
        /// Per-class Mochi customization ({body, accessory, aura}),
        /// deserialized from {@code org_class.mochi_config}. Present ONLY for
        /// CENTRE_CLASS avatars whose class has a saved config; null/absent for
        /// PERSONAL avatars and for class avatars with no config set. Mobile
        /// renders the class Mochi from this.
        @JsonInclude(JsonInclude.Include.NON_NULL)
        MochiConfig mochiConfig,
        /// The class this avatar belongs to. Present ONLY for a student's
        /// class-bound CENTRE_CLASS avatar; null/absent for PERSONAL and for the
        /// hidden class corpus. The mobile uses it to call leave-class.
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String classId
) {}
