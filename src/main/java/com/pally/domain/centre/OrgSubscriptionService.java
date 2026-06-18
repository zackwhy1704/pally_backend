package com.pally.domain.centre;

import com.pally.infrastructure.persistence.organization.ClassMembershipJpaEntity;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgClassJpaRepository;
import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
import com.pally.infrastructure.config.CacheConfig;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Manages the B2B subscription lifecycle for organisations:
 * pilot → active → expired/canceled, including DPA terms acceptance
 * and entitlement cache eviction on every state change.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class OrgSubscriptionService {

    private static final int PILOT_DAYS = 30;
    private static final int CAP_SOLO   = 15;
    private static final int CAP_CENTRE = 20;

    private final OrganizationJpaRepository orgRepo;
    private final OrgClassJpaRepository classRepo;
    private final ClassMembershipJpaRepository membershipRepo;
    private final CacheManager cacheManager;

    public void startPilot(String orgId, String tier, int classLimit) {
        OrganizationJpaEntity org = getOrThrow(orgId);
        if (OrganizationJpaEntity.STATUS_ACTIVE.equals(org.getSubStatus()))
            throw new BusinessException("Organisation is already active", 409);
        int cap = OrganizationJpaEntity.TIER_SOLO.equals(tier) ? CAP_SOLO : CAP_CENTRE;
        org.setTier(tier);
        org.setSubStatus(OrganizationJpaEntity.STATUS_PILOT);
        org.setPilotEndsAt(Instant.now().plus(Duration.ofDays(PILOT_DAYS)));
        org.setClassLimit(classLimit);
        org.setClassStudentCap(cap);
        orgRepo.save(org);
        evictAllStudents(orgId);
        log.info("[OrgSub] Pilot started orgId={} tier={} classLimit={} ends={}",
                orgId, tier, classLimit, org.getPilotEndsAt());
    }

    public void activate(String orgId, String billingRef) {
        OrganizationJpaEntity org = getOrThrow(orgId);
        org.setSubStatus(OrganizationJpaEntity.STATUS_ACTIVE);
        org.setStripeCustomerRef(billingRef);
        orgRepo.save(org);
        evictAllStudents(orgId);
        log.info("[OrgSub] Activated orgId={} ref={}", orgId, billingRef);
    }

    public void expire(String orgId) {
        OrganizationJpaEntity org = getOrThrow(orgId);
        org.setSubStatus(OrganizationJpaEntity.STATUS_EXPIRED);
        orgRepo.save(org);
        evictAllStudents(orgId);
        log.info("[OrgSub] Expired orgId={}", orgId);
    }

    public void cancel(String orgId) {
        OrganizationJpaEntity org = getOrThrow(orgId);
        org.setSubStatus(OrganizationJpaEntity.STATUS_CANCELED);
        orgRepo.save(org);
        evictAllStudents(orgId);
        log.info("[OrgSub] Canceled orgId={}", orgId);
    }

    public void acceptTerms(String orgId, String userId) {
        OrganizationJpaEntity org = getOrThrow(orgId);
        if (org.getTermsAcceptedAt() != null) return; // idempotent
        org.setTermsAcceptedAt(Instant.now());
        org.setTermsAcceptedBy(userId);
        orgRepo.save(org);
        log.info("[OrgSub] Terms accepted orgId={} by={}", orgId, userId);
    }

    /**
     * Returns true if the org is entitled (active subscription or live pilot).
     * Performs a lazy expiry flip on first read past the pilot deadline.
     */
    public boolean isEntitled(OrganizationJpaEntity org) {
        String status = org.getSubStatus();
        if (OrganizationJpaEntity.STATUS_ACTIVE.equals(status)) return true;
        if (OrganizationJpaEntity.STATUS_PILOT.equals(status)) {
            if (org.getPilotEndsAt() != null && Instant.now().isBefore(org.getPilotEndsAt())) return true;
            // Lazy flip
            org.setSubStatus(OrganizationJpaEntity.STATUS_EXPIRED);
            orgRepo.save(org);
            evictAllStudents(org.getId());
            log.info("[OrgSub] Lazy-expired orgId={}", org.getId());
        }
        return false;
    }

    /** Evicts entitlement cache for every active student linked to this org. */
    private void evictAllStudents(String orgId) {
        Cache cache = cacheManager.getCache(CacheConfig.ENTITLEMENT);
        if (cache == null) return;
        classRepo.findByOrganizationId(orgId).forEach(cls ->
            membershipRepo.findByClassId(cls.getId()).stream()
                .filter(m -> ClassMembershipJpaEntity.STATUS_ACTIVE.equals(m.getStatus()))
                .forEach(m -> cache.evict(m.getUserId()))
        );
    }

    private OrganizationJpaEntity getOrThrow(String orgId) {
        return orgRepo.findById(orgId)
            .orElseThrow(() -> new BusinessException("Organisation not found", 404));
    }
}
