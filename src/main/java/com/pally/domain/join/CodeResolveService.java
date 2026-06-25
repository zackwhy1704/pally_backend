package com.pally.domain.join;

import com.pally.infrastructure.persistence.group.StudyGroupJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgClassJpaRepository;
import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
import com.pally.shared.exception.BusinessException;
import org.springframework.stereotype.Service;

/**
 * Resolves a join code to a human-readable {@code {type, name, context}} with
 * NO side effects — this powers the Join surface's mandatory named confirmation
 * ("Join <em>Ms Tan's P5 Math</em>?") so a student can confirm what they're
 * joining BEFORE the actual commit. The real joins still go through the existing
 * redeem/join endpoints; this is a pure lookup.
 *
 * <p>Tries class first (the centre case), then study group. Parent-claim codes
 * are intentionally NOT resolved here — that flow keeps its own claim screen and
 * confirmation.
 */
@Service
public class CodeResolveService {

    private final OrgClassJpaRepository classRepo;
    private final OrganizationJpaRepository orgRepo;
    private final StudyGroupJpaRepository groupRepo;

    public CodeResolveService(OrgClassJpaRepository classRepo,
                              OrganizationJpaRepository orgRepo,
                              StudyGroupJpaRepository groupRepo) {
        this.classRepo = classRepo;
        this.orgRepo = orgRepo;
        this.groupRepo = groupRepo;
    }

    /** What a code resolves to. {@code context} is the centre name for a class, else null. */
    public record ResolvedCode(String type, String code, String name, String context) {}

    public ResolvedCode resolve(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            throw new BusinessException("code is required", 400);
        }
        String code = rawCode.trim().toUpperCase();

        var cls = classRepo.findByJoinCode(code);
        if (cls.isPresent()) {
            var c = cls.get();
            String centre = orgRepo.findById(c.getOrganizationId())
                    .map(OrganizationJpaEntity::getName)
                    .orElse(null);
            return new ResolvedCode("CLASS", code, c.getName(), centre);
        }

        var grp = groupRepo.findByInviteCode(code);
        if (grp.isPresent()) {
            return new ResolvedCode("GROUP", code, grp.get().getName(), null);
        }

        throw new BusinessException("That code doesn't match a class or group", 404);
    }
}
