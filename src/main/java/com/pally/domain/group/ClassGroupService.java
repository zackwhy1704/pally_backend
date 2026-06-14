package com.pally.domain.group;

import com.pally.infrastructure.persistence.group.GroupMemberJpaEntity;
import com.pally.infrastructure.persistence.group.GroupMemberJpaRepository;
import com.pally.infrastructure.persistence.group.GroupSystemPostJpaEntity;
import com.pally.infrastructure.persistence.group.GroupSystemPostJpaRepository;
import com.pally.infrastructure.persistence.group.StudyGroupJpaEntity;
import com.pally.infrastructure.persistence.group.StudyGroupJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgClassJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgClassJpaRepository;
import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Owns the lifecycle of CLASS-type study groups, which mirror a centre class's
 * enrolment. A class has exactly one CLASS group; its membership is synced from
 * {@code class_membership} (students join/leave the group as they're assigned /
 * removed) and the centre owner sits in it as a {@link GroupMemberJpaEntity#ROLE_TEACHER}.
 *
 * <p>CLASS groups are covered by the centre subscription, so they bypass the
 * student-premium gate enforced in the peer-group controller.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClassGroupService {

    private final StudyGroupJpaRepository groupRepo;
    private final GroupMemberJpaRepository memberRepo;
    private final GroupSystemPostJpaRepository systemPostRepo;
    private final OrgClassJpaRepository classRepo;
    private final OrganizationJpaRepository orgRepo;

    /**
     * Ensures the CLASS group for the given class exists, creating it (with the
     * centre owner as TEACHER) if absent. Idempotent. Returns the group.
     */
    @Transactional
    public StudyGroupJpaEntity ensureClassGroup(OrgClassJpaEntity cls) {
        return groupRepo.findByClassId(cls.getId()).orElseGet(() -> {
            StudyGroupJpaEntity g = new StudyGroupJpaEntity();
            g.setId(IdGenerator.newId());
            g.setName(cls.getName());
            g.setSubject(cls.getSubject());
            g.setInviteCode(uniqueInviteCode());
            g.setGroupType(StudyGroupJpaEntity.TYPE_CLASS);
            g.setClassId(cls.getId());
            // createdBy = centre owner (the teacher who manages it).
            String ownerId = orgRepo.findById(cls.getOrganizationId())
                    .map(OrganizationJpaEntity::getOwnerUserId)
                    .orElse(cls.getOrganizationId());
            g.setCreatedBy(ownerId);
            g.setCreatedAt(Instant.now());
            groupRepo.save(g);
            // The centre owner is the group's teacher (moderator).
            upsertMember(g.getId(), ownerId, GroupMemberJpaEntity.ROLE_TEACHER);
            log.info("[ClassGroup] created CLASS group={} class={} teacher={}",
                    g.getId(), cls.getId(), ownerId);
            return g;
        });
    }

    /**
     * Adds a student to the class's CLASS group (creating the group if needed).
     * Called when a student is assigned to the class. Idempotent.
     */
    @Transactional
    public void syncStudentJoin(OrgClassJpaEntity cls, String studentId) {
        StudyGroupJpaEntity g = ensureClassGroup(cls);
        if (!memberRepo.existsByGroupIdAndUserId(g.getId(), studentId)) {
            upsertMember(g.getId(), studentId, GroupMemberJpaEntity.ROLE_MEMBER);
            log.info("[ClassGroup] student={} joined CLASS group={}", studentId, g.getId());
        }
    }

    /**
     * Removes a student from the class's CLASS group. Called when a student is
     * unenrolled from the class. No-op if the group or membership is absent.
     */
    @Transactional
    public void syncStudentLeave(String classId, String studentId) {
        groupRepo.findByClassId(classId).ifPresent(g -> {
            memberRepo.deleteByGroupIdAndUserId(g.getId(), studentId);
            log.info("[ClassGroup] student={} removed from CLASS group={}", studentId, g.getId());
        });
    }

    /**
     * Posts a backend-authored announcement into a class's CLASS group feed.
     * No-op (logged) if the class has no group yet. Returns the post id, or null.
     */
    @Transactional
    public String postSystemMessage(String classId, String kind, String body, String refId) {
        StudyGroupJpaEntity g = groupRepo.findByClassId(classId).orElse(null);
        if (g == null) {
            log.warn("[ClassGroup] no CLASS group for class={} — skipping system post kind={}",
                    classId, kind);
            return null;
        }
        GroupSystemPostJpaEntity p = new GroupSystemPostJpaEntity();
        p.setId(IdGenerator.newId());
        p.setGroupId(g.getId());
        p.setKind(kind);
        p.setBody(body);
        p.setRefId(refId);
        p.setCreatedAt(Instant.now());
        systemPostRepo.save(p);
        log.info("[ClassGroup] system post kind={} class={} group={}", kind, classId, g.getId());
        return p.getId();
    }

    /**
     * One-time backfill: creates CLASS groups for existing classes that lack one
     * and joins their active members. Safe to run repeatedly.
     *
     * @return the number of CLASS groups created.
     */
    @Transactional
    public int backfillAllClasses(java.util.function.Function<String, java.util.List<String>> activeStudentIdsForClass) {
        int created = 0;
        for (OrgClassJpaEntity cls : classRepo.findAll()) {
            boolean existed = groupRepo.findByClassId(cls.getId()).isPresent();
            StudyGroupJpaEntity g = ensureClassGroup(cls);
            if (!existed) created++;
            for (String studentId : activeStudentIdsForClass.apply(cls.getId())) {
                if (!memberRepo.existsByGroupIdAndUserId(g.getId(), studentId)) {
                    upsertMember(g.getId(), studentId, GroupMemberJpaEntity.ROLE_MEMBER);
                }
            }
        }
        log.info("[ClassGroup] backfill created {} CLASS groups", created);
        return created;
    }

    /** True if the class already has a CLASS group. */
    public boolean hasClassGroup(String classId) {
        return groupRepo.findByClassId(classId).isPresent();
    }

    /**
     * Deletes the CLASS group for a class, if present. Group members and system
     * posts cascade via their {@code ON DELETE CASCADE} FK on study_groups.
     * Idempotent — a no-op when the class never had a group.
     */
    @Transactional
    public void deleteClassGroup(String classId) {
        groupRepo.findByClassId(classId).ifPresent(g -> {
            groupRepo.delete(g);
            log.info("[ClassGroup] deleted CLASS group={} class={}", g.getId(), classId);
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void upsertMember(String groupId, String userId, String role) {
        GroupMemberJpaEntity m = memberRepo
                .findById(new GroupMemberJpaEntity.PK(groupId, userId))
                .orElseGet(GroupMemberJpaEntity::new);
        m.setGroupId(groupId);
        m.setUserId(userId);
        m.setRole(role);
        if (m.getJoinedAt() == null) m.setJoinedAt(Instant.now());
        memberRepo.save(m);
    }

    private String uniqueInviteCode() {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        for (int attempt = 0; attempt < 8; attempt++) {
            StringBuilder sb = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                sb.append(alphabet.charAt(
                        ThreadLocalRandom.current().nextInt(alphabet.length())));
            }
            String code = sb.toString();
            if (!groupRepo.existsByInviteCode(code)) return code;
        }
        // Fall back to a longer code if 6-char space is saturated (practically never).
        return "C" + IdGenerator.newId().substring(0, 11).toUpperCase();
    }
}
