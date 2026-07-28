package com.pally.infrastructure.auth;

import com.pally.domain.account.AccountType;

import com.pally.domain.auth.dto.AuthResponse;
import com.pally.domain.shop.CharacterShopService;
import com.pally.domain.subscription.PremiumService;
import com.pally.infrastructure.persistence.organization.OrgStaffJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgStaffJpaRepository;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.AccountScheduledForDeletionException;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.exception.LinkRequiredException;
import com.pally.domain.consent.ConsentGuard;
import com.pally.shared.util.EmailNormalizer;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Year;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserJpaRepository userRepo;
    private final OrganizationJpaRepository orgRepo;
    private final OrgStaffJpaRepository staffRepo;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final CharacterShopService characterShopService;
    private final com.pally.domain.progress.BadgeService badgeService;
    private final com.pally.domain.progress.StreakService streakService;
    private final PremiumService premiumService;
    private final com.pally.domain.consent.UserAgeService userAgeService;
    private final com.pally.domain.consent.ConsentService consentService;
    private final DuplicateSignupNotifier duplicateSignupNotifier;
    private final AuthChallengeService authChallengeService;
    private final com.pally.infrastructure.email.EmailService emailService;

    @org.springframework.beans.factory.annotation.Value("${app.web-base-url:https://apalchi.com}")
    private String webBaseUrl;

    @org.springframework.beans.factory.annotation.Value("${account.deletion.grace-days:14}")
    private int deletionGraceDays;

    /**
     * ACCOUNT DELETION: a DELETION_PENDING account authenticating during grace must NOT
     * receive a session token — the session_epoch bump is the wall, and minting here
     * would be the hole in it. Throw the restore surface instead. Applied at every genuine
     * sign-in entry (password login + social sign-in); a brand-new account is never
     * DELETION_PENDING so this is a no-op on registration paths.
     */
    private void guardNotPendingDeletion(UserJpaEntity user) {
        if (ConsentGuard.STATUS_DELETION_PENDING.equals(user.getAccountStatus())) {
            Instant graceEndsAt = user.getDeletionRequestedAt() != null
                    ? user.getDeletionRequestedAt().plus(deletionGraceDays, ChronoUnit.DAYS)
                    : null;
            log.info("[Auth] Sign-in blocked — account {} is scheduled for deletion", user.getId());
            throw new AccountScheduledForDeletionException(graceEndsAt);
        }
    }

    @Transactional
    public AuthResponse register(String email, String password, String displayName) {
        return register(email, password, displayName, null, null);
    }

    @Transactional
    public AuthResponse register(String email, String password, String displayName, String role) {
        return register(email, password, displayName, role, null);
    }

    @Transactional
    public AuthResponse register(
            String email, String password, String displayName, String role, Integer birthYear) {
        return register(email, password, displayName, role, birthYear, null);
    }

    @Transactional
    public AuthResponse register(
            String email, String password, String displayName, String role,
            Integer birthYear, String parentEmail) {
        // Canonical email is the uniqueness key for EVERY lookup/store (trim+lowercase).
        email = EmailNormalizer.canonical(email);
        if (userRepo.existsByEmail(email)) {
            // INVARIANT: an account-creating endpoint NEVER issues a token for a
            // pre-existing account. Reject (409, no token) and notify the OWNER, not
            // the requester (generic error, no enumeration).
            duplicateSignupNotifier.notifyOwner(email);
            throw new BusinessException("Email already registered", 409);
        }

        // Sensible bounds on the optional birth YEAR (data minimisation: year only).
        // Lower bound (≥1950) is annotation-validated; the upper bound is dynamic
        // (must not be in the future) so it lives here, in Singapore wall-clock time.
        if (birthYear != null) {
            int currentYear = Year.now(ZoneId.of("Asia/Singapore")).getValue();
            if (birthYear < 1950 || birthYear > currentYear) {
                throw new BusinessException("Birth year must be between 1950 and " + currentYear, 400);
            }
        }

        boolean isParent = "parent".equalsIgnoreCase(role);
        boolean isAdult = "adult".equalsIgnoreCase(role); // web centre-admin (adults-only)
        boolean ageExempt = isParent || isAdult; // not a student data subject → no age gate
        // Age is REQUIRED for a STUDENT account (the age-gate can't fail safe without it).
        // Age-exempt adults (parent guardian, web centre-admin) register without one.
        if (!ageExempt && birthYear == null) {
            throw new BusinessException("Birth year is required", 400);
        }
        boolean under13 = !ageExempt && userAgeService.isUnder13(birthYear);
        // DEFAULT-DENY at the entry: no under-13 account without a parent/guardian email.
        if (under13 && (parentEmail == null || parentEmail.isBlank())) {
            throw new BusinessException(
                    "A parent or guardian email is required to create an account for a child under 13", 400);
        }

        UserJpaEntity user = new UserJpaEntity();
        user.setId(IdGenerator.newId());
        user.setEmail(email);
        user.setDisplayName(displayName != null ? displayName : "Player");
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setStars(0);
        user.setXp(0);
        user.setLevel(1);
        user.setStreakDays(0);
        user.setCreatedAt(Instant.now());
        user.setSetupComplete(false);
        // Student path only — age-exempt adults (parent / web centre-admin) store no
        // birth year (they're not the data subject under the age rule).
        if (!ageExempt) {
            user.setBirthYear(birthYear);
        }
        if (isParent) {
            user.setAccountType(AccountType.PARENT);
        } else if (isAdult) {
            user.setAccountType(AccountType.ADULT);
        }
        userRepo.save(user);

        log.info("[Auth] Registered new user id={} role={} under13={}",
                user.getId(), user.getAccountType(), under13);
        characterShopService.seedDefaultUnlocks(user.getId());
        if (under13) {
            // Half-elevated state: emails the parent a one-tap approval token and sets
            // accountStatus PENDING_CONSENT. The child can log in + use centre lessons,
            // but cannot upload own notes until approved. Trial starts at approval.
            consentService.requestParentConsent(user.getId(), parentEmail);
        } else {
            // 7-day cardless trial immediately for new 13+ accounts.
            premiumService.grantTrial(user.getId());
        }
        String token = jwtService.generateToken(user.getId(), user.getRole(), user.getSessionEpoch());
        return new AuthResponse(user.getId(), token, true, false, user.getAccountType());
    }

    /**
     * Existence check used by quick-onboard to choose register vs login WITHOUT
     * throwing a 409 across {@link #register}'s transactional boundary. Throwing
     * there would mark the caller's shared transaction rollback-only and make the
     * later commit fail with UnexpectedRollbackException.
     */
    @Transactional(readOnly = true)
    public boolean emailExists(String email) {
        return userRepo.existsByEmail(EmailNormalizer.canonical(email));
    }

    @Transactional
    public AuthResponse login(String email, String password) {
        UserJpaEntity user = userRepo.findByEmail(EmailNormalizer.canonical(email))
                .orElseThrow(() -> new BusinessException("Invalid email or password", 401));

        if (user.getPasswordHash() == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException("Invalid email or password", 401);
        }

        // Password verified — but a DELETION_PENDING account gets the restore surface,
        // never a session token (see guardNotPendingDeletion).
        guardNotPendingDeletion(user);

        // Daily login streak update (idempotent for same-day logins).
        updateLoginStreak(user);

        // Award streak badges if applicable. Runs in its OWN transaction so a
        // badge failure cannot mark this login's transaction rollback-only
        // (the swallowed exception below would otherwise poison the commit).
        try {
            badgeService.checkAndGrantMilestonesIsolated(user.getId());
        } catch (Exception ignored) {}
        // fall through to token mint below

        log.info("[Auth] Login success id={} streak={}",
                user.getId(), user.getStreakDays());
        String token = jwtService.generateToken(user.getId(), user.getRole(), user.getSessionEpoch());
        return new AuthResponse(user.getId(), token, false, user.isSetupComplete(), user.getAccountType());
    }

    /// Day-roll + freeze + milestone is now owned by StreakService; this
    /// wrapper preserves the existing daily-login XP bonus (5 XP per
    /// streak day, capped at 50). StreakService already persists streak +
    /// freezes + lastActiveDate before we add the XP on top.
    private void updateLoginStreak(UserJpaEntity user) {
        java.time.LocalDate last = user.getLastActiveDate();
        if (last != null && last.equals(java.time.LocalDate.now(com.pally.shared.util.PallyTime.SGT))) {
            return;
        }
        var result = streakService.recordActiveDay(user.getId());
        int bonus = Math.min(result.streakDays() * 5, 50);
        if (bonus > 0) {
            UserJpaEntity refreshed = userRepo.findById(user.getId()).orElse(user);
            refreshed.setXp(refreshed.getXp() + bonus);
            refreshed.setLevel(
                    com.pally.domain.progress.ProgressSummary
                            .computeLevel(refreshed.getXp()));
            userRepo.save(refreshed);
            log.info("[Auth] Streak day {} → +{} XP",
                    result.streakDays(), bonus);
        }
    }

    /**
     * Complete a PENDING_PROFILE (social) account OR a legacy null-birthYear account:
     * record the birth year, then flip status by age — under-13 → PENDING_CONSENT + parent
     * email; 13+ → ACTIVE (+ trial). Fresh session so the new status takes effect. This is
     * the single "collect the missing age" path for both the social DOB step (4.2) and the
     * legacy-null re-prompt (4.3).
     */
    @Transactional
    public AuthResponse completeProfile(String userId, Integer birthYear, String parentEmail) {
        UserJpaEntity u = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("Account not found", 404));
        if (birthYear == null) {
            throw new BusinessException("Birth year is required", 400);
        }
        int currentYear = Year.now(ZoneId.of("Asia/Singapore")).getValue();
        if (birthYear < 1950 || birthYear > currentYear) {
            throw new BusinessException("Birth year must be between 1950 and " + currentYear, 400);
        }
        boolean under13 = userAgeService.isUnder13(birthYear);
        if (under13 && (parentEmail == null || parentEmail.isBlank())) {
            throw new BusinessException(
                    "A parent or guardian email is required for a child under 13", 400);
        }
        boolean wasPendingProfile = ConsentGuard.STATUS_PENDING_PROFILE.equals(u.getAccountStatus());
        u.setBirthYear(birthYear);
        if (under13) {
            u.setAccountStatus(ConsentGuard.STATUS_PENDING);
            userRepo.save(u);
            consentService.requestParentConsent(u.getId(), parentEmail);
        } else {
            u.setAccountStatus(ConsentGuard.STATUS_ACTIVE);
            userRepo.save(u);
            if (wasPendingProfile) premiumService.grantTrial(u.getId()); // trial starts on completion
        }
        String token = jwtService.generateToken(u.getId(), u.getRole(), u.getSessionEpoch());
        return new AuthResponse(u.getId(), token, false, u.isSetupComplete(), u.getAccountType());
    }

    // ── Account linking (resolves LinkRequiredException) ─────────────────────

    /** Challenge A: verify the account password, then link the social provider sub. */
    @Transactional
    public AuthResponse linkSocialByPassword(String email, boolean emailVerified, String provider,
                                             String providerSub, String password) {
        UserJpaEntity u = userRepo.findByEmail(EmailNormalizer.canonical(email)).orElse(null);
        // Generic failure — never reveal whether the account/credential type exists.
        if (u == null || !emailVerified || u.getPasswordHash() == null
                || !passwordEncoder.matches(password, u.getPasswordHash())) {
            throw new BusinessException("Invalid email or password", 401);
        }
        return linkAndFreshSession(u, provider, providerSub);
    }

    /** Challenge B step 1: email a 6-digit code to a passwordless account's owner. */
    @Transactional
    public void requestSocialLinkCode(String email, boolean emailVerified, String provider,
                                      String providerSub) {
        if (!emailVerified) return; // never act on an unverified email
        String canonical = EmailNormalizer.canonical(email);
        var u = userRepo.findByEmail(canonical);
        if (u.isEmpty() || u.get().getPasswordHash() != null) return; // generic silence
        String code = authChallengeService.createLinkCode(u.get().getId(), provider, providerSub);
        try {
            emailService.sendHtml(canonical, "Your Apalchi sign-in code",
                    "<p>Enter this code to link " + provider + " sign-in to your account:</p>"
                    + "<p style=\"font-size:24px;font-weight:bold\">" + code + "</p>"
                    + "<p>It expires in 10 minutes. If you didn't request this, ignore this email.</p>");
        } catch (Exception e) {
            log.warn("[Auth] link-code email failed (ignored): {}", e.getMessage());
        }
    }

    /** Challenge B step 2: verify the emailed code, then link the social provider sub. */
    @Transactional
    public AuthResponse linkSocialByCode(String email, boolean emailVerified, String provider,
                                         String code) {
        UserJpaEntity u = userRepo.findByEmail(EmailNormalizer.canonical(email))
                .orElseThrow(() -> new BusinessException("Invalid or expired code", 401));
        var consumed = authChallengeService.consumeLinkCode(u.getId(), provider, code);
        if (consumed.isEmpty()) {
            throw new BusinessException("Invalid or expired code", 401);
        }
        return linkAndFreshSession(u, provider, consumed.get().providerSub());
    }

    /** Stamp the provider sub, INVALIDATE all existing sessions (epoch bump), fresh token. */
    private AuthResponse linkAndFreshSession(UserJpaEntity u, String provider, String providerSub) {
        u.setProvider(provider);
        u.setProviderSub(providerSub);
        u.setSessionEpoch(u.getSessionEpoch() + 1); // every prior session/token now invalid
        userRepo.save(u);
        String token = jwtService.generateToken(u.getId(), u.getRole(), u.getSessionEpoch());
        return new AuthResponse(u.getId(), token, false, u.isSetupComplete(), u.getAccountType());
    }

    // ── Password reset (real; replaces the no-op stub) ───────────────────────

    /** Best-effort: if the email owns an account, email a single-use reset link (≤1h). */
    @Transactional
    public void requestPasswordReset(String email) {
        String canonical = EmailNormalizer.canonical(email);
        var u = userRepo.findByEmail(canonical);
        if (u.isEmpty()) return; // generic — no account enumeration
        String token = authChallengeService.createResetToken(u.get().getId());
        String url = webBaseUrl + "/reset-password?token=" + token;
        try {
            emailService.sendHtml(canonical, "Reset your Apalchi password",
                    "<p>Tap the link to set a new password (expires in 1 hour):</p>"
                    + "<p><a href=\"" + url + "\">Reset my password</a></p>"
                    + "<p>If you didn't request this, ignore this email — nothing changed.</p>");
        } catch (Exception e) {
            log.warn("[Auth] reset email failed (ignored): {}", e.getMessage());
        }
    }

    /** Consume a reset token, set the new password, invalidate all sessions, notify. No auto-login. */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        String userId = authChallengeService.consumeResetToken(token)
                .orElseThrow(() -> new BusinessException("This reset link is invalid or has expired", 400));
        UserJpaEntity u = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("This reset link is invalid or has expired", 400));
        u.setPasswordHash(passwordEncoder.encode(newPassword));
        u.setSessionEpoch(u.getSessionEpoch() + 1); // kill all existing sessions after a reset
        userRepo.save(u);
        try {
            emailService.sendHtml(u.getEmail(), "Your Apalchi password was changed",
                    "<p>Your password was just changed. If this wasn't you, reset it again "
                    + "immediately and contact support.</p>");
        } catch (Exception e) {
            log.warn("[Auth] reset-notify email failed (ignored): {}", e.getMessage());
        }
        // Deliberately NO token returned — the user signs in fresh.
    }

    @Transactional
    public AuthResponse signInWithSocial(String email, boolean emailVerified, String displayName,
                                         String provider, String providerSub) {
        String canonical = EmailNormalizer.canonical(email);
        UserJpaEntity user = null;

        // 1. SUB-KEY first — the stable identity. A returning social user matches here
        //    regardless of an email change or a relay/real-email switch.
        if (provider != null && providerSub != null) {
            var bySub = userRepo.findByProviderAndProviderSub(provider, providerSub);
            if (bySub.isPresent()) user = bySub.get();
        }

        // 2. No sub match → the VERIFIED email path (legacy backfill + collision policy).
        //    Only a provider-VERIFIED email may match (an unverified email is
        //    attacker-controllable → never match/link).
        if (user == null && canonical != null && emailVerified) {
            var existing = userRepo.findByEmail(canonical);
            if (existing.isPresent()) {
                UserJpaEntity u = existing.get();
                if (u.getPasswordHash() != null) {
                    // DIFFERENT credential type (password). NEVER auto-link (the takeover
                    // vector) and NEVER duplicate — require explicit password linking.
                    throw new LinkRequiredException("PASSWORD", provider);
                }
                boolean sameOrUnkeyedProvider =
                        u.getProviderSub() == null
                        && (u.getProvider() == null || provider == null || provider.equals(u.getProvider()));
                if (sameOrUnkeyedProvider) {
                    // LAZY BACKFILL: a legacy social row matched by verified email once →
                    // stamp (provider, sub); sub-keyed on every future sign-in.
                    u.setProvider(provider);
                    u.setProviderSub(providerSub);
                    user = userRepo.save(u);
                } else if (provider != null && provider.equals(u.getProvider())) {
                    user = u; // already keyed to this provider
                } else {
                    // A social account of a DIFFERENT provider shares this email →
                    // require explicit (email-code) linking, never a silent merge.
                    throw new LinkRequiredException("EMAIL_CODE", provider);
                }
            }
        }

        if (user == null) {
            UserJpaEntity u = new UserJpaEntity();
            u.setId(IdGenerator.newId());
            u.setEmail(canonical);
            u.setProvider(provider);
            u.setProviderSub(providerSub);
            u.setDisplayName(displayName != null ? displayName : "Player");
            u.setStars(0);
            u.setXp(0);
            u.setLevel(1);
            u.setStreakDays(0);
            u.setCreatedAt(Instant.now());
            u.setSetupComplete(false);
            // Social sign-in never collects age → the new account is PENDING_PROFILE:
            // it can sign in, but every consent-gated action is server-blocked
            // (ConsentGuard.requireActive) until it completes the DOB step.
            u.setAccountStatus(ConsentGuard.STATUS_PENDING_PROFILE);
            user = userRepo.save(u);
        }

        boolean isNew = user.getCreatedAt().isAfter(Instant.now().minusSeconds(5));
        if (isNew) {
            characterShopService.seedDefaultUnlocks(user.getId());
        }

        // Same streak + badge progression as email login. Without this,
        // Google / Apple users never accumulate streak days even with
        // daily activity.
        updateLoginStreak(user);
        try {
            // Isolated tx: a badge failure must never poison this sign-in's commit.
            badgeService.checkAndGrantMilestonesIsolated(user.getId());
        } catch (Exception ignored) {
            // never block sign-in on badge math
        }

        // A returning DELETION_PENDING account gets the restore surface, not a session.
        guardNotPendingDeletion(user);

        log.info("[Auth] Social sign-in id={} new={} streak={}",
                user.getId(), isNew, user.getStreakDays());
        String token = jwtService.generateToken(user.getId(), user.getRole(), user.getSessionEpoch());
        return new AuthResponse(user.getId(), token, isNew, user.isSetupComplete(), user.getAccountType());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getUser(String userId) {
        UserJpaEntity user = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        boolean isOwner = orgRepo.findFirstByOwnerUserId(userId).isPresent();
        boolean isStaff = staffRepo.existsByUserIdAndStatus(userId, OrgStaffJpaEntity.STATUS_ACTIVE);
        boolean isCentreStaff = isOwner || isStaff;
        var m = new java.util.HashMap<String, Object>();
        m.put("userId", user.getId());
        m.put("email", user.getEmail() != null ? user.getEmail() : "");
        m.put("displayName", user.getDisplayName() != null ? user.getDisplayName() : "");
        m.put("setupComplete", user.isSetupComplete());
        m.put("childName", user.getChildName() != null ? user.getChildName() : "");
        m.put("accountStatus",
                user.getAccountStatus() != null ? user.getAccountStatus() : "ACTIVE");
        m.put("defaultAnswerMode",
                user.getDefaultAnswerMode() != null ? user.getDefaultAnswerMode() : "GUIDE");
        m.put("preferredLocale",
                user.getPreferredLocale() != null ? user.getPreferredLocale() : "en");
        m.put("role", user.getRole() != null ? user.getRole() : "USER");
        m.put("isCentreStaff", isCentreStaff);
        m.put("isOwner", isOwner);
        return m;
    }

    @Transactional
    public void updateDefaultAnswerMode(String userId, String mode) {
        String normalized = "ANSWER".equalsIgnoreCase(mode) ? "ANSWER" : "GUIDE";
        userRepo.findById(userId).ifPresent(u -> {
            u.setDefaultAnswerMode(normalized);
            userRepo.save(u);
        });
    }

    /// Update the user's preferred UI locale ('en' | 'zh'). Rejects an unsupported value with a 400
    /// (never silently defaults). This is UI chrome only — it does NOT change any avatar's
    /// content_language, and it never retags existing artifacts.
    @Transactional
    public void updatePreferredLocale(String userId, String locale) {
        String normalized = com.pally.domain.i18n.SupportedLanguage.validate(locale); // 400 if unsupported
        userRepo.findById(userId).ifPresent(u -> {
            u.setPreferredLocale(normalized);
            userRepo.save(u);
        });
    }

    // ACCOUNT DELETION Phase 2: the AuthService.deleteAccount path (bearer-only immediate
    // hard-delete behind DELETE /auth/account) was REMOVED — the endpoint now returns 410
    // GONE. Deletion goes exclusively through AccountDeletionService.requestDeletion
    // (re-auth + grace) and the two background reapers. There is one deletion path again.

    @Transactional
    public void updateChildName(String userId, String childName) {
        UserJpaEntity user = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        if (childName != null && !childName.isBlank()) {
            user.setChildName(childName);
            userRepo.save(user);
            log.info("[Auth] Updated child name id={}", userId);
        }
    }

    @Transactional
    public AuthResponse completeSetup(String userId, String childName, Integer yearLevel, String curriculum) {
        UserJpaEntity user = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));

        user.setChildName(childName);
        user.setYearLevel(yearLevel);
        user.setCurriculum(curriculum);
        user.setSetupComplete(true);
        userRepo.save(user);

        log.info("[Auth] Setup complete id={}", userId);
        String token = jwtService.generateToken(userId, user.getRole(), user.getSessionEpoch());
        return new AuthResponse(userId, token, false, true, user.getAccountType());
    }
}
