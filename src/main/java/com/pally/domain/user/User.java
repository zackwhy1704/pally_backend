package com.pally.domain.user;

import com.pally.domain.account.AccountType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
public class User {
    private String id;
    private String email;
    private String displayName;
    private String childName;
    private String parentId;
    private AccountType accountType = AccountType.SOLO;
    private int stars;
    private int xp;
    private int level;
    private int streakDays;
    private int longestStreak;
    private int streakFreezes = 1;
    private LocalDate lastActiveDate;
    private String streakMilestonesReached;
    private boolean isPremium;
    private String referralCode;
    private boolean emailVerified;
    private String accountStatus = "ACTIVE";
    private Integer birthYear;
    private String trialStatus = "NONE";
    private Instant trialStartedAt;
    private Instant trialEndsAt;
    private Instant lastSlotChangeAt;
    private String centreId;
    private String cohortLabel;
    private String role = "USER";

    /// UI-chrome language the user chose ('en' | 'zh'). Independent of any avatar's
    /// content_language — a child with an English phone UI can be in a 华文 class.
    /// Defaults to 'en' so every existing account is unaffected.
    private String preferredLocale = "en";

    // ── Parental controls ─────────────────────────────────────────────────
    private boolean screenTimeEnabled;
    private int screenTimeMinutes = 60;

    // ── Family pairing ────────────────────────────────────────────────────
    private String linkCode;
    private Instant linkCodeExpiresAt;
}
