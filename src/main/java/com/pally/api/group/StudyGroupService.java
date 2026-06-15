package com.pally.api.group;

import com.pally.domain.knowledge.RelevanceScore;
import com.pally.domain.knowledge.port.RelevancePort;
import com.pally.domain.progress.XpService;
import com.pally.domain.subscription.Entitlements;
import com.pally.domain.subscription.PremiumService;
import com.pally.infrastructure.persistence.group.GroupMemberJpaEntity;
import com.pally.infrastructure.persistence.group.GroupMemberJpaRepository;
import com.pally.infrastructure.persistence.group.GroupReportJpaEntity;
import com.pally.infrastructure.persistence.group.GroupReportJpaRepository;
import com.pally.infrastructure.persistence.group.GroupSharedNoteJpaEntity;
import com.pally.infrastructure.persistence.group.GroupSharedNoteJpaRepository;
import com.pally.infrastructure.persistence.group.GroupSystemPostJpaRepository;
import com.pally.infrastructure.persistence.group.StudyGroupJpaEntity;
import com.pally.infrastructure.persistence.group.StudyGroupJpaRepository;
import com.pally.infrastructure.persistence.knowledge.WikiPageJpaEntity;
import com.pally.infrastructure.persistence.knowledge.WikiPageJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.exception.UpgradeRequiredException;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Application service for entitlement-gated study groups — owns all logic + repo
 * access so {@link StudyGroupController} stays a thin HTTP delegator. Returns the
 * exact response shapes the controller wraps, so the HTTP contract is identical.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StudyGroupService {

    private static final int SHARE_XP = 10;
    private static final double SHARE_BLOCK_BELOW = 0.20;
    private static final double SHARE_WARN_BELOW = 0.45;
    private static final int RELEVANCE_SAMPLE_CHARS = 1500;

    private final StudyGroupJpaRepository groupRepo;
    private final GroupMemberJpaRepository memberRepo;
    private final GroupSharedNoteJpaRepository sharedNoteRepo;
    private final GroupReportJpaRepository reportRepo;
    private final GroupSystemPostJpaRepository systemPostRepo;
    private final WikiPageJpaRepository wikiPageRepo;
    private final RelevancePort relevancePort;
    private final UserJpaRepository userRepo;
    private final PremiumService premiumService;
    private final XpService xpService;

    // ── List ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> listMyGroups(String userId) {
        var groups = groupRepo.findGroupsForUser(userId);
        var summaries = groups.stream().map(this::toSummary).toList();
        return Map.of("groups", summaries);
    }

    // ── Create ──────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> createGroup(String userId, Map<String, String> body) {
        ensureGroupsEntitled(userId);
        String name = body.getOrDefault("name", "").trim();
        if (name.isBlank() || name.length() > 100) {
            throw new BusinessException("Group name is required (1-100 chars)", 400);
        }
        String subject = body.get("subject");

        StudyGroupJpaEntity g = new StudyGroupJpaEntity();
        g.setId(IdGenerator.newId());
        g.setName(name);
        g.setSubject(subject);
        g.setInviteCode(uniqueInviteCode());
        g.setCreatedBy(userId);
        g.setCreatedAt(Instant.now());
        groupRepo.save(g);

        addMember(g.getId(), userId, GroupMemberJpaEntity.ROLE_OWNER);

        log.info("[Groups] user={} created group={} name={}", userId, g.getId(), name);
        return toSummary(g);
    }

    // ── Join ────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> join(String userId, Map<String, String> body) {
        ensureGroupsEntitled(userId);
        String inviteCode = body.getOrDefault("inviteCode", "").trim().toUpperCase();
        if (inviteCode.isBlank()) {
            throw new BusinessException("Invite code is required", 400);
        }
        var group = groupRepo.findByInviteCode(inviteCode)
                .orElseThrow(() -> new BusinessException("Invite code not found", 404));
        // CLASS groups are enrolment-synced: students cannot self-join by code.
        if (StudyGroupJpaEntity.TYPE_CLASS.equals(group.getGroupType())) {
            throw new BusinessException(
                    "This is a class group — you join it by being enrolled in the class.", 403);
        }
        if (memberRepo.existsByGroupIdAndUserId(group.getId(), userId)) {
            return toSummary(group);
        }
        addMember(group.getId(), userId, GroupMemberJpaEntity.ROLE_MEMBER);
        log.info("[Groups] user={} joined group={}", userId, group.getId());
        return toSummary(group);
    }

    // ── Detail ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> getGroup(String userId, String groupId) {
        ensureMember(groupId, userId);
        var group = groupRepo.findById(groupId)
                .orElseThrow(() -> new BusinessException("Group not found", 404));

        List<Map<String, Object>> members = new ArrayList<>();
        for (var m : memberRepo.findByGroupId(groupId)) {
            var u = userRepo.findById(m.getUserId()).orElse(null);
            members.add(Map.of(
                    "userId", m.getUserId(),
                    "displayName", u != null && u.getDisplayName() != null
                            ? u.getDisplayName() : "Member",
                    "role", m.getRole(),
                    "joinedAt", m.getJoinedAt().toString()));
        }

        List<Map<String, Object>> notes = sharedNoteRepo
                .findRecentByGroupId(groupId).stream()
                // BLOCKED notes stay invisible to everyone except the sharer.
                .filter(n -> !"BLOCKED".equals(n.getRelevanceStatus())
                        || n.getSharedBy().equals(userId))
                .map(n -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", n.getId());
                    m.put("wikiPageId", n.getWikiPageId());
                    m.put("avatarId", n.getAvatarId() == null ? "" : n.getAvatarId());
                    m.put("title", n.getTitle() == null ? "" : n.getTitle());
                    m.put("sharedBy", n.getSharedBy());
                    m.put("sharedAt", n.getSharedAt().toString());
                    m.put("relevanceStatus", n.getRelevanceStatus());
                    if (n.getRelevanceScore() != null) {
                        m.put("relevanceScore", n.getRelevanceScore());
                    }
                    if (n.getRelevanceReason() != null) {
                        m.put("relevanceReason", n.getRelevanceReason());
                    }
                    return m;
                })
                .toList();

        // Backend-authored announcements (answers released, muddiest, challenge).
        List<Map<String, Object>> systemPosts = systemPostRepo
                .findTop50ByGroupIdOrderByCreatedAtDesc(groupId).stream()
                .map(p -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", p.getId());
                    m.put("kind", p.getKind());
                    m.put("body", p.getBody());
                    m.put("refId", p.getRefId());
                    m.put("createdAt", p.getCreatedAt().toString());
                    return m;
                })
                .toList();

        Map<String, Object> response = new HashMap<>(toSummary(group));
        response.put("members", members);
        response.put("sharedNotes", notes);
        response.put("systemPosts", systemPosts);
        return response;
    }

    // ── Share a wiki page ───────────────────────────────────────────────

    @Transactional
    public Map<String, Object> shareNote(String userId, String groupId, Map<String, String> body) {
        ensureMember(groupId, userId);
        String wikiPageId = body.get("wikiPageId");
        if (wikiPageId == null || wikiPageId.isBlank()) {
            throw new BusinessException("wikiPageId is required", 400);
        }
        var group = groupRepo.findById(groupId)
                .orElseThrow(() -> new BusinessException("Group not found", 404));

        WikiPageJpaEntity page = wikiPageRepo.findById(wikiPageId)
                .orElseThrow(() -> new BusinessException("Wiki page not found", 404));

        GroupSharedNoteJpaEntity n = new GroupSharedNoteJpaEntity();
        n.setId(IdGenerator.newId());
        n.setGroupId(groupId);
        n.setWikiPageId(wikiPageId);
        n.setAvatarId(page.getAvatarId());
        n.setTitle(body.get("title") != null ? body.get("title") : page.getTitle());
        n.setSharedBy(userId);
        n.setSharedAt(Instant.now());

        // Re-run relevance against the group's subject. Best-effort: a Claude
        // outage shouldn't block sharing — fall back to OK in that case.
        applyShareRelevance(n, group, page);

        if ("BLOCKED".equals(n.getRelevanceStatus())) {
            throw new BusinessException(
                    "That note doesn't match the group's subject. Try a more on-topic page.", 422);
        }
        sharedNoteRepo.save(n);
        log.info("[Groups] share group={} note={} status={} score={}",
                groupId, n.getId(), n.getRelevanceStatus(), n.getRelevanceScore());

        // Award XP once per (user, wikiPage, group) — dedup so re-sharing the
        // same page doesn't farm rewards. Check BEFORE saving completes the txn.
        boolean firstShare = !sharedNoteRepo
                .existsByGroupIdAndWikiPageIdAndSharedBy(groupId, wikiPageId, userId);
        int xpGranted = 0;
        int starsGranted = 0;
        if (firstShare) {
            xpGranted = SHARE_XP;
            xpService.awardFlat(userId, SHARE_XP);
            starsGranted = (int) Math.round(SHARE_XP * 0.5);
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("id", n.getId());
        resp.put("wikiPageId", n.getWikiPageId());
        resp.put("avatarId", n.getAvatarId() == null ? "" : n.getAvatarId());
        resp.put("title", n.getTitle() == null ? "" : n.getTitle());
        resp.put("relevanceStatus", n.getRelevanceStatus());
        resp.put("xpGranted", xpGranted);
        resp.put("starsGranted", starsGranted);
        if (n.getRelevanceScore() != null) {
            resp.put("relevanceScore", n.getRelevanceScore());
        }
        if (n.getRelevanceReason() != null) {
            resp.put("relevanceReason", n.getRelevanceReason());
        }
        return resp;
    }

    // ── Report a member or a shared note ────────────────────────────────

    @Transactional
    public Map<String, Object> report(String userId, String groupId, Map<String, String> body) {
        ensureMember(groupId, userId);
        String targetUserId = body.get("targetUserId");
        String targetNoteId = body.get("targetNoteId");
        String reason = body.get("reason");
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("reason is required", 400);
        }
        if (reason.length() > 50) {
            throw new BusinessException("reason must be 50 chars or less", 400);
        }
        boolean hasUser = targetUserId != null && !targetUserId.isBlank();
        boolean hasNote = targetNoteId != null && !targetNoteId.isBlank();
        if (hasUser == hasNote) {
            throw new BusinessException(
                    "Provide exactly one of targetUserId or targetNoteId", 400);
        }
        if (hasUser && targetUserId.equals(userId)) {
            throw new BusinessException("You can't report yourself", 400);
        }
        if (hasNote) {
            var note = sharedNoteRepo.findById(targetNoteId).orElseThrow(
                    () -> new BusinessException("Note not found", 404));
            if (!note.getGroupId().equals(groupId)) {
                throw new BusinessException("Note doesn't belong to this group", 400);
            }
        }
        GroupReportJpaEntity r = new GroupReportJpaEntity();
        r.setId(IdGenerator.newId());
        r.setGroupId(groupId);
        r.setReporterUserId(userId);
        r.setTargetUserId(hasUser ? targetUserId : null);
        r.setTargetNoteId(hasNote ? targetNoteId : null);
        r.setReason(reason);
        r.setDetails(body.get("details"));
        r.setStatus(GroupReportJpaEntity.STATUS_OPEN);
        r.setCreatedAt(Instant.now());
        reportRepo.save(r);
        log.info("[Groups] report group={} by={} target={} reason={}",
                groupId, userId,
                hasUser ? "user:" + targetUserId : "note:" + targetNoteId, reason);
        return Map.of("id", r.getId());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> listReports(String userId, String groupId) {
        ensureOwner(groupId, userId);
        var rows = reportRepo.findByGroupIdOrderByCreatedAtDesc(groupId).stream()
                .map(r -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", r.getId());
                    m.put("reporterUserId", r.getReporterUserId());
                    m.put("targetUserId", r.getTargetUserId());
                    m.put("targetNoteId", r.getTargetNoteId());
                    m.put("reason", r.getReason());
                    m.put("details", r.getDetails());
                    m.put("status", r.getStatus());
                    m.put("createdAt", r.getCreatedAt().toString());
                    return m;
                })
                .toList();
        return Map.of("reports", rows);
    }

    // ── Kick a member ───────────────────────────────────────────────────

    @Transactional
    public void kickMember(String userId, String groupId, String targetUserId) {
        ensureModerator(groupId, userId);
        if (userId.equals(targetUserId)) {
            throw new BusinessException(
                    "Owners can't kick themselves — leave the group instead", 400);
        }
        if (!memberRepo.existsByGroupIdAndUserId(groupId, targetUserId)) {
            throw new BusinessException("That user isn't in this group", 404);
        }
        memberRepo.deleteByGroupIdAndUserId(groupId, targetUserId);
        log.info("[Groups] owner={} kicked user={} from group={}", userId, targetUserId, groupId);
    }

    // ── Leave ───────────────────────────────────────────────────────────

    @Transactional
    public void leave(String userId, String groupId) {
        // Students can't leave a CLASS group — membership tracks class enrolment.
        // Only a TEACHER may (which removes their own moderator seat).
        var group = groupRepo.findById(groupId)
                .orElseThrow(() -> new BusinessException("Group not found", 404));
        if (StudyGroupJpaEntity.TYPE_CLASS.equals(group.getGroupType())
                && !isTeacher(groupId, userId)) {
            throw new BusinessException(
                    "You can't leave a class group — ask your centre to unenrol you.", 403);
        }
        memberRepo.deleteByGroupIdAndUserId(groupId, userId);
        log.info("[Groups] user={} left group={}", userId, groupId);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private void addMember(String groupId, String userId, String role) {
        GroupMemberJpaEntity m = new GroupMemberJpaEntity();
        m.setGroupId(groupId);
        m.setUserId(userId);
        m.setRole(role);
        m.setJoinedAt(Instant.now());
        memberRepo.save(m);
    }

    private void ensureMember(String groupId, String userId) {
        if (!memberRepo.existsByGroupIdAndUserId(groupId, userId)) {
            throw new BusinessException("You are not a member of this group", 403);
        }
    }

    private void ensureOwner(String groupId, String userId) {
        var member = memberRepo
                .findById(new GroupMemberJpaEntity.PK(groupId, userId))
                .orElseThrow(() -> new BusinessException(
                        "You are not a member of this group", 403));
        // CLASS-group moderation is by the TEACHER; PEER-group by the OWNER.
        if (!GroupMemberJpaEntity.ROLE_OWNER.equals(member.getRole())
                && !GroupMemberJpaEntity.ROLE_TEACHER.equals(member.getRole())) {
            throw new BusinessException(
                    "Only the group owner or teacher can do that", 403);
        }
    }

    /**
     * Allows the action only for a privileged caller: in a PEER group the OWNER,
     * in a CLASS group the TEACHER. A plain student MEMBER gets 403.
     */
    private void ensureModerator(String groupId, String userId) {
        ensureOwner(groupId, userId);
    }

    private boolean isTeacher(String groupId, String userId) {
        return memberRepo
                .findById(new GroupMemberJpaEntity.PK(groupId, userId))
                .map(m -> GroupMemberJpaEntity.ROLE_TEACHER.equals(m.getRole()))
                .orElse(false);
    }

    private void applyShareRelevance(GroupSharedNoteJpaEntity note,
                                     StudyGroupJpaEntity group,
                                     WikiPageJpaEntity page) {
        String subject = group.getSubject();
        if (subject == null || subject.isBlank()) {
            note.setRelevanceStatus("OK");
            return;
        }
        String content = page.getContent() == null ? "" : page.getContent();
        if (content.isBlank()) {
            note.setRelevanceStatus("OK");
            return;
        }
        String sample = content.length() > RELEVANCE_SAMPLE_CHARS
                ? content.substring(0, RELEVANCE_SAMPLE_CHARS)
                : content;
        try {
            RelevanceScore score = relevancePort.check(
                    subject, "Group: " + group.getName(), sample);
            double v = score.value();
            note.setRelevanceScore((float) v);
            note.setRelevanceReason(score.reason());
            if (v < SHARE_BLOCK_BELOW) {
                note.setRelevanceStatus("BLOCKED");
            } else if (v < SHARE_WARN_BELOW) {
                note.setRelevanceStatus("WARNING");
            } else {
                note.setRelevanceStatus("OK");
            }
        } catch (Exception e) {
            log.warn("[Groups] share relevance check failed, defaulting OK: {}", e.getMessage());
            note.setRelevanceStatus("OK");
        }
    }

    /**
     * Enforces the groups entitlement gate. FREE (SPARK) tier users cannot
     * create or join groups — they receive a 402 UPGRADE_REQUIRED.
     */
    private void ensureGroupsEntitled(String userId) {
        var tier = premiumService.resolveTier(userId);
        if (!Entitlements.forTier(tier).groups()) {
            throw new UpgradeRequiredException("GROUPS");
        }
    }

    private Map<String, Object> toSummary(StudyGroupJpaEntity g) {
        long memberCount = memberRepo.findByGroupId(g.getId()).size();
        Map<String, Object> m = new HashMap<>();
        m.put("id", g.getId());
        m.put("name", g.getName());
        m.put("subject", g.getSubject());
        m.put("inviteCode", g.getInviteCode());
        m.put("createdBy", g.getCreatedBy());
        m.put("createdAt", g.getCreatedAt().toString());
        m.put("memberCount", memberCount);
        m.put("groupType", g.getGroupType());
        m.put("classId", g.getClassId());
        return m;
    }

    private String uniqueInviteCode() {
        // 6-char alphanumeric. Retry on the rare collision; uniqueness is
        // enforced by the DB index.
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
        throw new BusinessException("Could not generate invite code", 500);
    }
}
