package com.pally.domain.organization;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.group.ClassGroupService;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaEntity;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgClassJpaEntity;
import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Shared class-enrollment provisioning, used by both the teacher-driven roster
 * assign ({@code POST .../classes/{id}/members}) and student self-join by class
 * code ({@code POST /centre/redeem-class-code}). Keeping it in one place means a
 * student provisioned either way gets the identical branded CENTRE_CLASS avatar
 * (bound to the class corpus, carrying the class's Mochi look) and the same
 * CLASS-group membership.
 */
@Service
@RequiredArgsConstructor
public class ClassEnrollmentService {

    private static final Logger log = LoggerFactory.getLogger(ClassEnrollmentService.class);

    private final AvatarRepository avatarRepository;
    private final ClassMembershipJpaRepository membershipRepo;
    private final ClassGroupService classGroupService;
    private final OrganizationJpaRepository orgRepo;

    /**
     * Provisions (or returns the existing) branded class avatar for a student and
     * records their active membership + CLASS-group join. Idempotent: a student who
     * already has an active membership keeps their avatar — no re-provisioning.
     *
     * @return the student's class avatar id
     */
    @Transactional
    public String enroll(OrgClassJpaEntity cls, String studentId) {
        Optional<ClassMembershipJpaEntity> existing =
                membershipRepo.findByClassIdAndUserId(cls.getId(), studentId);
        if (existing.isPresent()
                && ClassMembershipJpaEntity.STATUS_ACTIVE.equals(existing.get().getStatus())
                && existing.get().getStudentAvatarId() != null) {
            return existing.get().getStudentAvatarId();
        }

        // B2B gates: only apply when the org is in an active B2B lifecycle state
        // (PILOT or ACTIVE). Legacy/teacher-only orgs with tier=NONE are exempt.
        OrganizationJpaEntity org = orgRepo.findById(cls.getOrganizationId())
                .orElseThrow(() -> new BusinessException("Organisation not found", 404));

        // Hard block: expired or cancelled centres must not accept new students.
        if (OrganizationJpaEntity.STATUS_EXPIRED.equals(org.getSubStatus())
                || OrganizationJpaEntity.STATUS_CANCELED.equals(org.getSubStatus())) {
            throw new BusinessException(
                    "This centre's subscription has ended — new students cannot join.", 403);
        }

        boolean isManagedB2B = OrganizationJpaEntity.STATUS_PILOT.equals(org.getSubStatus())
                || OrganizationJpaEntity.STATUS_ACTIVE.equals(org.getSubStatus());
        if (isManagedB2B) {
            // DPA gate: terms must be accepted before any student can join a managed centre
            if (org.getTermsAcceptedAt() == null) {
                throw new BusinessException(
                        "Your centre admin must accept educator terms before students can join.", 403);
            }
            // Student cap — count only STUDENT role so staff preview seats don't consume quota
            long activeStudents = membershipRepo.countByClassIdAndStatusAndRole(
                    cls.getId(), ClassMembershipJpaEntity.STATUS_ACTIVE,
                    ClassMembershipJpaEntity.ROLE_STUDENT);
            if (org.getClassStudentCap() > 0 && activeStudents >= org.getClassStudentCap()) {
                throw new BusinessException(
                        "Class is full (" + org.getClassStudentCap() + " students) — "
                        + "open another class to add more.", 403);
            }
        }

        // Branded closed-book class avatar the student studies with — CENTRE_CLASS
        // so it is excluded from the economy and rendered with the class look, and
        // bound to the class for analytics grouping.
        Avatar avatar = Avatar.create(
                studentId,
                brandedName(cls),
                parseSubject(cls.getSubject()),
                parseCharacter(cls.getCharacterType()),
                cls.getLevel(), null);
        avatar.markCentreClassAvatar();
        avatar.setClassId(cls.getId());
        avatar.setCorpusAvatarId(cls.getCorpusAvatarId());
        applyClassConfig(avatar, cls);
        Avatar saved = avatarRepository.save(avatar);

        ClassMembershipJpaEntity m = existing.orElseGet(ClassMembershipJpaEntity::new);
        if (m.getId() == null) m.setId(IdGenerator.newId());
        m.setClassId(cls.getId());
        m.setUserId(studentId);
        m.setStudentAvatarId(saved.getId());
        m.setStatus(ClassMembershipJpaEntity.STATUS_ACTIVE);
        m.setRole(ClassMembershipJpaEntity.ROLE_STUDENT);
        membershipRepo.save(m);

        classGroupService.syncStudentJoin(cls, studentId);
        log.info("[Enroll] student={} class={} avatar={}", studentId, cls.getId(), saved.getId());
        return saved.getId();
    }

    /**
     * Provisions (or returns the existing) branded class avatar for a staff member
     * for preview purposes. Unlike {@link #enroll}, this:
     * <ul>
     *   <li>Skips the student cap check — staff seats are not paid slots.</li>
     *   <li>Skips the EXPIRED/CANCELLED block — teachers can always preview.</li>
     *   <li>Records {@code role = STAFF} so the row is excluded from cap/analytics.</li>
     *   <li>Does NOT call {@code classGroupService.syncStudentJoin} — staff are not in student groups.</li>
     * </ul>
     *
     * @return the staff member's class avatar id
     */
    @Transactional
    public String enrollStaff(OrgClassJpaEntity cls, String staffUserId) {
        Optional<ClassMembershipJpaEntity> existing =
                membershipRepo.findByClassIdAndUserId(cls.getId(), staffUserId);
        if (existing.isPresent()
                && ClassMembershipJpaEntity.STATUS_ACTIVE.equals(existing.get().getStatus())
                && existing.get().getStudentAvatarId() != null) {
            return existing.get().getStudentAvatarId();
        }

        Avatar avatar = Avatar.create(
                staffUserId,
                brandedName(cls),
                parseSubject(cls.getSubject()),
                parseCharacter(cls.getCharacterType()),
                cls.getLevel(), null);
        avatar.markCentreClassAvatar();
        avatar.setClassId(cls.getId());
        avatar.setCorpusAvatarId(cls.getCorpusAvatarId());
        applyClassConfig(avatar, cls);
        Avatar saved = avatarRepository.save(avatar);

        ClassMembershipJpaEntity m = existing.orElseGet(ClassMembershipJpaEntity::new);
        if (m.getId() == null) m.setId(IdGenerator.newId());
        m.setClassId(cls.getId());
        m.setUserId(staffUserId);
        m.setStudentAvatarId(saved.getId());
        m.setStatus(ClassMembershipJpaEntity.STATUS_ACTIVE);
        m.setRole(ClassMembershipJpaEntity.ROLE_STAFF);
        membershipRepo.save(m);

        log.info("[Enroll] staff-preview: userId={} class={} avatar={}", staffUserId, cls.getId(), saved.getId());
        return saved.getId();
    }

    /**
     * Reverses {@link #enroll}: removes the student's membership in a class, deletes
     * their branded class avatar (its wiki/files/chat cascade on avatar delete, like
     * class deletion does for a single student), and drops them from the CLASS group.
     * Does NOT touch the corpus avatar or any other student. No-op-safe: throws if the
     * caller has no active membership so the API can return a clear 404.
     */
    @Transactional
    public void leave(OrgClassJpaEntity cls, String studentId) {
        ClassMembershipJpaEntity m = membershipRepo
                .findByClassIdAndUserId(cls.getId(), studentId)
                .filter(x -> ClassMembershipJpaEntity.STATUS_ACTIVE.equals(x.getStatus()))
                .orElseThrow(() -> new BusinessException(
                        "You are not enrolled in this class.", 404));
        if (m.getStudentAvatarId() != null) {
            avatarRepository.deleteById(m.getStudentAvatarId());
        }
        membershipRepo.delete(m);
        classGroupService.syncStudentLeave(cls.getId(), studentId);
        log.info("[Enroll] student={} left class={}", studentId, cls.getId());
    }

    private static String brandedName(OrgClassJpaEntity cls) {
        return cls.getBrandName() != null && !cls.getBrandName().isBlank()
                ? cls.getBrandName() : cls.getName();
    }

    private static void applyClassConfig(Avatar a, OrgClassJpaEntity cls) {
        a.setCentreBrandName(brandedName(cls));
        a.setCentreAccentColor(cls.getAccentColor());
        a.setCosmeticEyewear(cls.getCosmeticEyewear());
        a.setCosmeticClothes(cls.getCosmeticClothes());
        a.setCosmeticShoes(cls.getCosmeticShoes());
    }

    private static CharacterType parseCharacter(String s) {
        if (s == null || s.isBlank()) return CharacterType.MOCHI;
        try {
            return CharacterType.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return CharacterType.MOCHI;
        }
    }

    private static Subject parseSubject(String s) {
        if (s == null || s.isBlank()) return Subject.GENERAL;
        try {
            return Subject.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Subject.GENERAL;
        }
    }
}
