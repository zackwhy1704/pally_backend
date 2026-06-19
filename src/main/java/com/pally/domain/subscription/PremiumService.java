package com.pally.domain.subscription;

import com.pally.domain.account.AccountType;
import com.pally.domain.centre.OrgSubscriptionService;
import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.config.CacheConfig;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaEntity;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgClassJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgClassJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgStaffJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgStaffJpaRepository;
import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
import com.pally.infrastructure.persistence.subscription.SubscriptionJpaEntity;
import com.pally.infrastructure.persistence.subscription.SubscriptionJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Single source of truth for premium entitlement.
 *
 * <p>A user is considered premium if:
 *  - their own subscriptions row has {@code status ∈ ACTIVE_STATUSES}, OR
 *  - they are a CHILD whose parent's subscription is active, OR
 *  - (M6, when wired) their centre's subscription is active.
 *
 * <p>{@link #resolve} also refreshes the denormalised {@code users.is_premium}
 * cache so a follow-up call from any other path (chat gating, paywall guard)
 * can read straight off the user row without re-resolving the family graph.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PremiumService {

    private static final Set<String> ACTIVE_STATUSES = Set.of("active", "trialing");
    public  static final Duration   TRIAL_DURATION  = Duration.ofDays(7);
    public  static final String     TRIAL_NONE      = "NONE";
    public  static final String     TRIAL_ACTIVE    = "ACTIVE";
    public  static final String     TRIAL_EXPIRED   = "EXPIRED";
    public  static final String     TRIAL_CONVERTED = "CONVERTED";

    private final UserRepository userRepo;
    private final SubscriptionJpaRepository subRepo;
    private final ClassMembershipJpaRepository membershipRepo;
    private final OrgClassJpaRepository classRepo;
    private final OrganizationJpaRepository orgRepo;
    private final OrgStaffJpaRepository staffRepo;
    private final OrgSubscriptionService orgSubService;

    public record Entitlement(
            boolean isPremium,
            String source,       // SELF | PARENT | CENTRE | NONE
            String plan,
            String status,
            Instant trialEndsAt
    ) {}

    /// Cached: same userId hits Postgres at most once per ENTITLEMENT TTL
    /// (60s). Eviction fires on every premium-write path (Stripe webhook,
    /// family link change) so a cancellation can't be hidden by a stale
    /// cache for more than that TTL even if eviction is missed.
    @Cacheable(value = CacheConfig.ENTITLEMENT, key = "#userId")
    @Transactional
    public Entitlement resolve(String userId) {
        User user = userRepo.findById(userId).orElse(null);
        if (user == null) {
            return new Entitlement(false, "NONE", null, "free", null);
        }

        // Admin gets Max tier for internal testing — placed first so it wins
        // unconditionally. Not centre-sourced (may route to Sonnet).
        if ("ADMIN".equals(user.getRole())) {
            persistFlag(user, true);
            return new Entitlement(true, "ADMIN", "max", "active", null);
        }

        // Self check first — own subscription wins over inherited so the
        // displayed plan label matches what the user is paying for.
        SubscriptionJpaEntity own = subRepo.findById(userId).orElse(null);
        if (own != null && ACTIVE_STATUSES.contains(own.getStatus())) {
            persistFlag(user, true);
            return new Entitlement(true, "SELF",
                    own.getPlan(), own.getStatus(),
                    isTrialing(own) ? own.getCurrentPeriodEnd() : null);
        }

        // Inherit from parent for CHILD accounts.
        if (user.getAccountType() == AccountType.CHILD && user.getParentId() != null) {
            SubscriptionJpaEntity parentSub =
                    subRepo.findById(user.getParentId()).orElse(null);
            if (parentSub != null && ACTIVE_STATUSES.contains(parentSub.getStatus())) {
                persistFlag(user, true);
                return new Entitlement(true, "PARENT",
                        parentSub.getPlan(), parentSub.getStatus(),
                        isTrialing(parentSub) ? parentSub.getCurrentPeriodEnd() : null);
            }
        }

        // Centre staff/owner — Pro on Haiku regardless of class enrollment.
        // Runs before resolveCentre so staff get Pro even with no class membership.
        Entitlement staffEnt = resolveStaff(user);
        if (staffEnt != null) return staffEnt;

        // Centre premium — student inherits from the centre they're enrolled in.
        // Runs between PARENT and TRIAL so a paying parent still wins over a free pilot.
        Entitlement centre = resolveCentre(user);
        if (centre != null) return centre;

        // Cardless trial — inserted between paid sources and free/NONE so
        // it never shadows a live Stripe subscription.
        Entitlement trial = resolveLocalTrial(user);
        if (trial != null) return trial;

        // No active subscription. Don't expose the cancelled-plan label so the
        // UI doesn't have to special-case "premium=false, plan=family_monthly".
        persistFlag(user, false);
        String status = own != null ? own.getStatus() : "free";
        return new Entitlement(false, "NONE", null, status, null);
    }

    /// Force-refresh the user's flag — call from webhook handlers when the
    /// subscription state changes asynchronously. Evicts the cached
    /// entitlement entry first so the resolve() call inside doesn't
    /// hand back the stale value via @Cacheable's read-through.
    @CacheEvict(value = CacheConfig.ENTITLEMENT, key = "#userId")
    @Transactional
    public void refreshFlag(String userId) {
        resolve(userId);
    }

    /// Public eviction hook for non-subscription writes that still need
    /// to flush the entitlement cache (e.g. parent-link claim flips a
    /// CHILD's inherited premium status).
    @CacheEvict(value = CacheConfig.ENTITLEMENT, key = "#userId")
    public void evictEntitlement(String userId) {
        // Method body is empty by design — the annotation does the work.
    }

    private void persistFlag(User user, boolean isPremium) {
        if (user.isPremium() == isPremium) return;
        user.setPremium(isPremium);
        userRepo.save(user);
        log.info("[Premium] user={} is_premium={}", user.getId(), isPremium);
    }

    private boolean isTrialing(SubscriptionJpaEntity sub) {
        return "trialing".equals(sub.getStatus());
    }

    // ── Centre entitlement ────────────────────────────────────────────────────

    /**
     * Returns a CENTRE entitlement if the user is an active member of any class
     * whose org has an entitled subscription (active or live pilot). Returns null
     * if no entitled centre membership exists.
     *
     * <p>The {@code @Cacheable} on {@link #resolve} means this runs at most once
     * per 60s TTL per user. {@code OrgSubscriptionService.evictAllStudents()} handles
     * invalidation on org lifecycle changes.
     */
    private Entitlement resolveCentre(User user) {
        List<ClassMembershipJpaEntity> memberships = membershipRepo.findByUserId(user.getId())
                .stream()
                .filter(m -> ClassMembershipJpaEntity.STATUS_ACTIVE.equals(m.getStatus()))
                .toList();
        for (ClassMembershipJpaEntity m : memberships) {
            OrgClassJpaEntity cls = classRepo.findById(m.getClassId()).orElse(null);
            if (cls == null) continue;
            OrganizationJpaEntity org = orgRepo.findById(cls.getOrganizationId()).orElse(null);
            if (org == null) continue;
            if (orgSubService.isEntitled(org)) {
                String status = OrganizationJpaEntity.STATUS_PILOT.equals(org.getSubStatus())
                        ? "trialing" : "active";
                Instant ends = OrganizationJpaEntity.STATUS_PILOT.equals(org.getSubStatus())
                        ? org.getPilotEndsAt() : null;
                persistFlag(user, true);
                return new Entitlement(true, "CENTRE", org.getTier().toLowerCase(), status, ends);
            }
        }
        return null;
    }

    // ── Owner / teacher entitlement ───────────────────────────────────────────

    /**
     * Returns a CENTRE/Pro entitlement for centre owners and active staff members,
     * gated on the org being in an entitled lifecycle state (PILOT or ACTIVE).
     * isCentreSourced=true → ModelRouter always routes these users to Haiku.
     * Returns null if the user is neither an owner nor active staff of any entitled org.
     */
    private Entitlement resolveStaff(User user) {
        // Owner path: does this user own any org?
        List<OrganizationJpaEntity> ownedOrgs = orgRepo.findByOwnerUserId(user.getId());
        for (OrganizationJpaEntity org : ownedOrgs) {
            if (orgSubService.isEntitled(org)) {
                String status = OrganizationJpaEntity.STATUS_PILOT.equals(org.getSubStatus())
                        ? "trialing" : "active";
                Instant ends = OrganizationJpaEntity.STATUS_PILOT.equals(org.getSubStatus())
                        ? org.getPilotEndsAt() : null;
                persistFlag(user, true);
                log.info("[Premium] staff/owner entitlement: user={} org={}", user.getId(), org.getId());
                return new Entitlement(true, "CENTRE", "pro", status, ends);
            }
        }
        // Staff path: is this user an active org_staff member of an entitled org?
        OrgStaffJpaEntity staffRow = staffRepo
                .findFirstByUserIdAndStatus(user.getId(), OrgStaffJpaEntity.STATUS_ACTIVE)
                .orElse(null);
        if (staffRow != null) {
            OrganizationJpaEntity org = orgRepo.findById(staffRow.getOrgId()).orElse(null);
            if (org != null && orgSubService.isEntitled(org)) {
                String status = OrganizationJpaEntity.STATUS_PILOT.equals(org.getSubStatus())
                        ? "trialing" : "active";
                Instant ends = OrganizationJpaEntity.STATUS_PILOT.equals(org.getSubStatus())
                        ? org.getPilotEndsAt() : null;
                persistFlag(user, true);
                log.info("[Premium] teacher entitlement: user={} org={}", user.getId(), org.getId());
                return new Entitlement(true, "CENTRE", "pro", status, ends);
            }
        }
        return null;
    }

    // ── Local cardless trial ──────────────────────────────────────────────────

    private Entitlement resolveLocalTrial(User user) {
        if (!TRIAL_ACTIVE.equals(user.getTrialStatus())) return null;
        Instant ends = user.getTrialEndsAt();
        if (ends == null) return null;
        Instant now = Instant.now();
        if (now.isBefore(ends)) {
            persistFlag(user, true);
            return new Entitlement(true, "TRIAL", "trial", "trialing", ends);
        }
        // Lazy expiry — flip to EXPIRED on first read after the boundary.
        user.setTrialStatus(TRIAL_EXPIRED);
        userRepo.save(user);
        persistFlag(user, false);
        log.info("[Trial] Expired userId={}", user.getId());
        return null;
    }

    /**
     * Tier + centre-sourcing context for callers that need both bits
     * (e.g. model routing where CENTRE students must never get Sonnet).
     */
    public record TierContext(SubscriptionTier tier, boolean isCentreSourced) {}

    /**
     * Resolves the user's tier based on their active entitlement.
     *
     * <p>Plan string matching is intentionally loose (contains) so both
     * {@code pro_monthly} and {@code pro_annual} resolve to PRO, etc.
     * Legacy plans ({@code individual_monthly}, {@code admin}, any old
     * {@code centre_*} rows migrated to MAX) fall through to MAX.
     */
    public SubscriptionTier resolveTier(String userId) {
        return tierFromEntitlement(resolve(userId));
    }

    /**
     * Resolves tier + centre flag in a single {@link #resolve} call.
     * Use this in model-routing paths so CENTRE students are never
     * accidentally routed to Sonnet.
     */
    public TierContext resolveTierContext(String userId) {
        Entitlement ent = resolve(userId);
        // ADMIN is not centre-sourced — may use Sonnet on complex questions.
        // CENTRE source (staff + students) is always centre-sourced → Haiku only.
        boolean isCentreSourced = "CENTRE".equals(ent.source());
        return new TierContext(tierFromEntitlement(ent), isCentreSourced);
    }

    private SubscriptionTier tierFromEntitlement(Entitlement ent) {
        if (!ent.isPremium()) return SubscriptionTier.FREE;
        if ("ADMIN".equals(ent.source()))  return SubscriptionTier.MAX;
        if ("CENTRE".equals(ent.source())) return SubscriptionTier.PRO;
        if ("TRIAL".equals(ent.source()))  return SubscriptionTier.MAX;
        String plan = ent.plan() != null ? ent.plan().toLowerCase() : "";
        if (plan.contains("family")) return SubscriptionTier.FAMILY;
        if (plan.contains("max"))    return SubscriptionTier.MAX;
        if (plan.contains("pro"))    return SubscriptionTier.PRO;
        return SubscriptionTier.MAX; // legacy plans
    }

    /**
     * Grants a 7-day cardless trial. Call once when an account becomes ACTIVE:
     * new registration (immediate) OR under-13 consent approval.
     * Idempotent — does nothing if a trial is already running or converted.
     */
    @CacheEvict(value = CacheConfig.ENTITLEMENT, key = "#userId")
    @Transactional
    public void grantTrial(String userId) {
        User user = userRepo.findById(userId).orElse(null);
        if (user == null) return;
        if (!TRIAL_NONE.equals(user.getTrialStatus())) return; // already used
        Instant now = Instant.now();
        user.setTrialStatus(TRIAL_ACTIVE);
        user.setTrialStartedAt(now);
        user.setTrialEndsAt(now.plus(TRIAL_DURATION));
        userRepo.save(user);
        log.info("[Trial] Granted 7-day trial userId={} ends={}", userId, user.getTrialEndsAt());
    }

    /**
     * Marks the trial as CONVERTED when the user subscribes via Stripe.
     * The SELF check in resolve() already wins; this just keeps the audit trail clean.
     */
    @CacheEvict(value = CacheConfig.ENTITLEMENT, key = "#userId")
    @Transactional
    public void convertTrial(String userId) {
        userRepo.findById(userId).ifPresent(user -> {
            if (TRIAL_ACTIVE.equals(user.getTrialStatus())
                    || TRIAL_EXPIRED.equals(user.getTrialStatus())) {
                user.setTrialStatus(TRIAL_CONVERTED);
                userRepo.save(user);
                log.info("[Trial] Converted userId={}", userId);
            }
        });
    }

    /**
     * Returns trial info for the Usage/Today endpoint without re-resolving
     * the full entitlement (avoids a second DB hit on the happy path).
     */
    public record TrialInfo(
            boolean trialActive,
            Instant trialEndsAt,
            long trialDaysLeft,
            long trialHoursLeft,
            String trialStatus
    ) {}

    @Transactional(readOnly = true)
    public TrialInfo getTrialInfo(String userId) {
        User user = userRepo.findById(userId).orElse(null);
        if (user == null || user.getTrialEndsAt() == null) {
            return new TrialInfo(false, null, 0, 0, TRIAL_NONE);
        }
        Instant now  = Instant.now();
        Instant ends = user.getTrialEndsAt();
        boolean active = TRIAL_ACTIVE.equals(user.getTrialStatus()) && now.isBefore(ends);
        long hours = active ? Duration.between(now, ends).toHours() : 0;
        long days  = active ? hours / 24 : 0;
        return new TrialInfo(active, active ? ends : null, days, hours,
                user.getTrialStatus() != null ? user.getTrialStatus() : TRIAL_NONE);
    }
}
