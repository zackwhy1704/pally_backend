package com.pally.domain.centre;

import com.pally.infrastructure.persistence.organization.ClassMembershipJpaEntity;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgClassJpaRepository;
import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Nightly job (02:00 SGT) that irrevocably removes student memberships for
 * organisations whose pilot expired more than {@code pilot.purge.after-days}
 * days ago. Disabled by default — enable via {@code PILOT_PURGE_ENABLED=true}
 * in Railway environment variables.
 *
 * <p>The org row is kept for audit; only membership records are touched.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "pilot.purge.enabled", havingValue = "true")
public class PilotPurgeScheduler {

    @Value("${pilot.purge.after-days:30}")
    private int purgeAfterDays;

    private final OrganizationJpaRepository orgRepo;
    private final OrgClassJpaRepository classRepo;
    private final ClassMembershipJpaRepository membershipRepo;

    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Singapore")
    @Transactional
    public void purgeExpiredPilots() {
        Instant purgeDeadline = Instant.now().minus(Duration.ofDays(purgeAfterDays));
        List<OrganizationJpaEntity> expired = orgRepo.findBySubStatusAndPilotEndsAtBefore(
                OrganizationJpaEntity.STATUS_EXPIRED, purgeDeadline);

        for (OrganizationJpaEntity org : expired) {
            log.info("[PilotPurge] Purging expired pilot orgId={} pilotEndsAt={}",
                    org.getId(), org.getPilotEndsAt());
            classRepo.findByOrganizationId(org.getId()).forEach(cls -> {
                membershipRepo.findByClassId(cls.getId()).forEach(m -> {
                    if (ClassMembershipJpaEntity.STATUS_ACTIVE.equals(m.getStatus())) {
                        m.setStatus(ClassMembershipJpaEntity.STATUS_REMOVED);
                        membershipRepo.save(m);
                    }
                });
            });
            // Mark purged — keep org row for audit
            org.setSubStatus("PURGED");
            orgRepo.save(org);
        }
    }
}
