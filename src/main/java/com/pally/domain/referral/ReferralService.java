package com.pally.domain.referral;

import com.pally.domain.progress.UserRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.infrastructure.persistence.referral.ReferralJpaEntity;
import com.pally.infrastructure.persistence.referral.ReferralJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;

/**
 * Owns referral codes (lazy-issue + lookup), redemption recording
 * (anti-self / anti-double), and the activation hook the quiz path
 * calls when a referee submits their first answer.
 *
 * <p>The reward (bonus stars/XP) is intentionally paid on ACTIVATION not
 * signup so a referrer can't farm fake accounts. {@link #ACTIVATION_XP}
 * and {@link #ACTIVATION_STARS} go to both sides.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReferralService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LENGTH = 6;

    public static final int ACTIVATION_XP = 50;
    public static final int ACTIVATION_STARS = 25;
    /// Tier ladder used for {@code nextTierAt} on /referral/me — first tier
    /// at 3 activated friends, then 5, 10. Keep the cliff visible.
    public static final int[] TIERS = {3, 5, 10};

    private final UserJpaRepository userRepo;
    private final ReferralJpaRepository referralRepo;
    private final UserRepository userDomainRepo;

    /// Returns the user's code, generating one on first call so we don't
    /// backfill at migration time.
    @Transactional
    public String ensureCodeFor(String userId) {
        UserJpaEntity user = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        if (user.getReferralCode() != null && !user.getReferralCode().isBlank()) {
            return user.getReferralCode();
        }
        String code = generateUniqueCode();
        user.setReferralCode(code);
        userRepo.save(user);
        return code;
    }

    /// Validates + records a pending redemption. The reward fires only when
    /// {@link #onFirstQuizAnswer} runs.
    @Transactional
    public void redeem(String refereeUserId, String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            throw new BusinessException("code is required", 400);
        }
        String code = rawCode.trim().toUpperCase();
        if (referralRepo.findByRefereeUserId(refereeUserId).isPresent()) {
            throw new BusinessException(
                    "You've already redeemed a referral code.", 409);
        }
        UserJpaEntity referrer = userRepo.findByReferralCode(code)
                .orElseThrow(() -> new BusinessException(
                        "That referral code doesn't exist.", 404));
        if (referrer.getId().equals(refereeUserId)) {
            throw new BusinessException(
                    "You can't refer yourself.", 400);
        }
        ReferralJpaEntity r = new ReferralJpaEntity();
        r.setId(IdGenerator.newId());
        r.setReferrerUserId(referrer.getId());
        r.setRefereeUserId(refereeUserId);
        r.setStatus(ReferralJpaEntity.STATUS_PENDING);
        r.setCreatedAt(Instant.now());
        referralRepo.save(r);
        log.info("[Referral] redeemed referrer={} referee={} (pending)",
                referrer.getId(), refereeUserId);
    }

    /// Called from the quiz path on a user's FIRST answer ever. Idempotent:
    /// already-activated referrals are skipped, missing referrals no-op.
    /// Item 10.1 gate: payout requires referee.email_verified — a kid
    /// can still PLAY without verification, but rewards don't mint until
    /// the email is verified (closes a fake-signup farming hole).
    @Transactional
    public void onFirstQuizAnswer(String refereeUserId) {
        Optional<ReferralJpaEntity> maybe =
                referralRepo.findByRefereeUserId(refereeUserId);
        if (maybe.isEmpty()) return;
        ReferralJpaEntity r = maybe.get();
        if (ReferralJpaEntity.STATUS_ACTIVATED.equals(r.getStatus())) return;

        boolean verified = userRepo.findById(refereeUserId)
                .map(UserJpaEntity::isEmailVerified)
                .orElse(false);
        if (!verified) {
            // Don't flip the row yet — leave it pending so the reward
            // mints on a later trigger once the user verifies. Same
            // method is safe to call again after verify-email returns.
            log.info("[Referral] referee={} unverified — withholding reward",
                    refereeUserId);
            return;
        }

        r.setStatus(ReferralJpaEntity.STATUS_ACTIVATED);
        r.setActivatedAt(Instant.now());
        referralRepo.save(r);

        // Pay both sides through the standard XP/stars path so it shows
        // in activity_log and triggers level-up celebrations.
        try {
            userDomainRepo.addXpAndStars(
                    r.getReferrerUserId(), ACTIVATION_XP, ACTIVATION_STARS);
            userDomainRepo.addXpAndStars(
                    refereeUserId, ACTIVATION_XP, ACTIVATION_STARS);
        } catch (Exception e) {
            // Don't unwind the activation — the row is already flipped,
            // and a missed reward is a rare manual-credit job vs. a stuck
            // pending forever.
            log.warn("[Referral] reward payout failed for {}/{} : {}",
                    r.getReferrerUserId(), refereeUserId, e.getMessage());
        }
        log.info("[Referral] activated referrer={} referee={} (+{}xp/+{}stars)",
                r.getReferrerUserId(), refereeUserId,
                ACTIVATION_XP, ACTIVATION_STARS);
    }

    public int nextTier(long activated) {
        for (int t : TIERS) {
            if (activated < t) return t;
        }
        return TIERS[TIERS.length - 1];
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            char[] buf = new char[CODE_LENGTH];
            for (int i = 0; i < CODE_LENGTH; i++) {
                buf[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
            }
            String candidate = new String(buf);
            if (userRepo.findByReferralCode(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new BusinessException(
                "Could not allocate a referral code — try again", 503);
    }
}
