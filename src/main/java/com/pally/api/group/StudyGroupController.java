package com.pally.api.group;

import com.pally.domain.knowledge.RelevanceScore;
import com.pally.domain.knowledge.port.RelevancePort;
import com.pally.infrastructure.persistence.flag.UserFeatureFlagJpaRepository;
import com.pally.infrastructure.persistence.group.GroupMemberJpaEntity;
import com.pally.infrastructure.persistence.group.GroupMemberJpaRepository;
import com.pally.infrastructure.persistence.group.GroupReportJpaEntity;
import com.pally.infrastructure.persistence.group.GroupReportJpaRepository;
import com.pally.infrastructure.persistence.group.GroupSharedNoteJpaEntity;
import com.pally.infrastructure.persistence.group.GroupSharedNoteJpaRepository;
import com.pally.infrastructure.persistence.group.StudyGroupJpaEntity;
import com.pally.infrastructure.persistence.group.StudyGroupJpaRepository;
import com.pally.infrastructure.persistence.knowledge.WikiPageJpaEntity;
import com.pally.infrastructure.persistence.knowledge.WikiPageJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Pilot-gated collaborative study groups. The {@code groups_enabled}
 * feature flag (V24) controls visibility client-side; backend stays open
 * to any authenticated caller so we can pilot without admin gating logic.
 *
 * <p>Free tier caps at 1 group membership; premium gating is server-side
 * intent and currently only enforced as a hard cap of {@link #FREE_GROUP_CAP}
 * for any non-flagged user. Flagged users (pilot/premium) bypass the cap.
 */
@RestController
@RequestMapping("/api/v1/groups")
@RequiredArgsConstructor
@Slf4j
public class StudyGroupController {

    private static final int FREE_GROUP_CAP = 1;
    private static final String PILOT_FLAG = "groups_enabled";

    private final StudyGroupJpaRepository groupRepo;
    private final GroupMemberJpaRepository memberRepo;
    private final GroupSharedNoteJpaRepository sharedNoteRepo;
    private final GroupReportJpaRepository reportRepo;
    private final WikiPageJpaRepository wikiPageRepo;
    private final RelevancePort relevancePort;
    private final UserJpaRepository userRepo;
    private final UserFeatureFlagJpaRepository flagRepo;

    /// Relevance thresholds — share is hard-blocked below BLOCK, soft-warned
    /// between BLOCK and WARN. Tuned against the same scale the upload-time
    /// relevance check uses, so the UX stays consistent across surfaces.
    private static final double SHARE_BLOCK_BELOW = 0.20;
    private static final double SHARE_WARN_BELOW = 0.45;
    private static final int RELEVANCE_SAMPLE_CHARS = 1500;

    // ── List ────────────────────────────────────────────────────────────

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> listMyGroups(
            @AuthenticationPrincipal String userId) {
        var groups = groupRepo.findGroupsForUser(userId);
        var summaries = groups.stream().map(this::toSummary).toList();
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("groups", summaries)));
    }

    // ── Create ──────────────────────────────────────────────────────────

    @PostMapping
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> createGroup(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        ensureWithinCap(userId);
        String name = body.getOrDefault("name", "").trim();
        if (name.isBlank() || name.length() > 100) {
            throw new BusinessException(
                    "Group name is required (1-100 chars)", 400);
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

        log.info("[Groups] user={} created group={} name={}",
                userId, g.getId(), name);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(toSummary(g)));
    }

    // ── Join ────────────────────────────────────────────────────────────

    @PostMapping("/join")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> join(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        ensureWithinCap(userId);
        String inviteCode = body.getOrDefault("inviteCode", "").trim()
                .toUpperCase();
        if (inviteCode.isBlank()) {
            throw new BusinessException("Invite code is required", 400);
        }
        var group = groupRepo.findByInviteCode(inviteCode)
                .orElseThrow(() -> new BusinessException(
                        "Invite code not found", 404));
        if (memberRepo.existsByGroupIdAndUserId(group.getId(), userId)) {
            return ResponseEntity.ok(ApiResponse.success(toSummary(group)));
        }
        addMember(group.getId(), userId, GroupMemberJpaEntity.ROLE_MEMBER);
        log.info("[Groups] user={} joined group={}", userId, group.getId());
        return ResponseEntity.ok(ApiResponse.success(toSummary(group)));
    }

    // ── Detail ──────────────────────────────────────────────────────────

    @GetMapping("/{groupId}")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> getGroup(
            @AuthenticationPrincipal String userId,
            @PathVariable String groupId) {
        ensureMember(groupId, userId);
        var group = groupRepo.findById(groupId)
                .orElseThrow(() -> new BusinessException("Group not found", 404));

        List<Map<String, Object>> members = new ArrayList<>();
        for (var m : memberRepo.findByGroupId(groupId)) {
            var u = userRepo.findById(m.getUserId()).orElse(null);
            members.add(Map.of(
                    "userId", m.getUserId(),
                    "displayName", u != null && u.getDisplayName() != null
                            ? u.getDisplayName()
                            : "Member",
                    "role", m.getRole(),
                    "joinedAt", m.getJoinedAt().toString()));
        }

        List<Map<String, Object>> notes = sharedNoteRepo
                .findRecentByGroupId(groupId).stream()
                // BLOCKED notes stay invisible to everyone except the sharer
                // so an off-topic share isn't pushed to other kids' feeds.
                .filter(n -> !"BLOCKED".equals(n.getRelevanceStatus())
                        || n.getSharedBy().equals(userId))
                .map(n -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", n.getId());
                    m.put("wikiPageId", n.getWikiPageId());
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

        Map<String, Object> response = new HashMap<>(toSummary(group));
        response.put("members", members);
        response.put("sharedNotes", notes);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ── Share a wiki page ───────────────────────────────────────────────

    @PostMapping("/{groupId}/share")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> shareNote(
            @AuthenticationPrincipal String userId,
            @PathVariable String groupId,
            @RequestBody Map<String, String> body) {
        ensureMember(groupId, userId);
        String wikiPageId = body.get("wikiPageId");
        if (wikiPageId == null || wikiPageId.isBlank()) {
            throw new BusinessException("wikiPageId is required", 400);
        }
        var group = groupRepo.findById(groupId)
                .orElseThrow(() -> new BusinessException("Group not found", 404));

        GroupSharedNoteJpaEntity n = new GroupSharedNoteJpaEntity();
        n.setId(IdGenerator.newId());
        n.setGroupId(groupId);
        n.setWikiPageId(wikiPageId);
        n.setTitle(body.get("title"));
        n.setSharedBy(userId);
        n.setSharedAt(Instant.now());

        // Re-run relevance against the group's subject. Best-effort: a
        // Claude outage shouldn't block sharing — fall back to OK in that
        // case so the share isn't silently lost.
        applyShareRelevance(n, group);

        if ("BLOCKED".equals(n.getRelevanceStatus())) {
            throw new BusinessException(
                    "That note doesn't match the group's subject. "
                            + "Try a more on-topic page.",
                    422);
        }
        sharedNoteRepo.save(n);
        log.info("[Groups] share group={} note={} status={} score={}",
                groupId, n.getId(), n.getRelevanceStatus(),
                n.getRelevanceScore());
        Map<String, Object> resp = new HashMap<>();
        resp.put("id", n.getId());
        resp.put("wikiPageId", n.getWikiPageId());
        resp.put("title", n.getTitle() == null ? "" : n.getTitle());
        resp.put("relevanceStatus", n.getRelevanceStatus());
        if (n.getRelevanceScore() != null) {
            resp.put("relevanceScore", n.getRelevanceScore());
        }
        if (n.getRelevanceReason() != null) {
            resp.put("relevanceReason", n.getRelevanceReason());
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(resp));
    }

    // ── Report a member or a shared note ────────────────────────────────

    @PostMapping("/{groupId}/report")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> report(
            @AuthenticationPrincipal String userId,
            @PathVariable String groupId,
            @RequestBody Map<String, String> body) {
        ensureMember(groupId, userId);
        String targetUserId = body.get("targetUserId");
        String targetNoteId = body.get("targetNoteId");
        String reason = body.get("reason");
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("reason is required", 400);
        }
        if (reason.length() > 50) {
            throw new BusinessException(
                    "reason must be 50 chars or less", 400);
        }
        boolean hasUser = targetUserId != null && !targetUserId.isBlank();
        boolean hasNote = targetNoteId != null && !targetNoteId.isBlank();
        if (hasUser == hasNote) {
            throw new BusinessException(
                    "Provide exactly one of targetUserId or targetNoteId",
                    400);
        }
        if (hasUser && targetUserId.equals(userId)) {
            throw new BusinessException("You can't report yourself", 400);
        }
        if (hasNote) {
            var note = sharedNoteRepo.findById(targetNoteId).orElseThrow(
                    () -> new BusinessException("Note not found", 404));
            if (!note.getGroupId().equals(groupId)) {
                throw new BusinessException(
                        "Note doesn't belong to this group", 400);
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
                hasUser ? "user:" + targetUserId : "note:" + targetNoteId,
                reason);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(Map.of("id", r.getId())));
    }

    @GetMapping("/{groupId}/reports")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> listReports(
            @AuthenticationPrincipal String userId,
            @PathVariable String groupId) {
        ensureOwner(groupId, userId);
        var rows = reportRepo
                .findByGroupIdOrderByCreatedAtDesc(groupId).stream()
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
        return ResponseEntity.ok(ApiResponse.success(Map.of("reports", rows)));
    }

    // ── Kick a member ───────────────────────────────────────────────────

    @DeleteMapping("/{groupId}/members/{targetUserId}")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> kickMember(
            @AuthenticationPrincipal String userId,
            @PathVariable String groupId,
            @PathVariable String targetUserId) {
        ensureOwner(groupId, userId);
        if (userId.equals(targetUserId)) {
            throw new BusinessException(
                    "Owners can't kick themselves — leave the group instead",
                    400);
        }
        if (!memberRepo.existsByGroupIdAndUserId(groupId, targetUserId)) {
            throw new BusinessException(
                    "That user isn't in this group", 404);
        }
        memberRepo.deleteByGroupIdAndUserId(groupId, targetUserId);
        log.info("[Groups] owner={} kicked user={} from group={}",
                userId, targetUserId, groupId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ── Leave ───────────────────────────────────────────────────────────

    @DeleteMapping("/{groupId}/leave")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> leave(
            @AuthenticationPrincipal String userId,
            @PathVariable String groupId) {
        memberRepo.deleteByGroupIdAndUserId(groupId, userId);
        log.info("[Groups] user={} left group={}", userId, groupId);
        return ResponseEntity.ok(ApiResponse.success(null));
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
            throw new BusinessException(
                    "You are not a member of this group", 403);
        }
    }

    private void ensureOwner(String groupId, String userId) {
        var member = memberRepo
                .findById(new GroupMemberJpaEntity.PK(groupId, userId))
                .orElseThrow(() -> new BusinessException(
                        "You are not a member of this group", 403));
        if (!GroupMemberJpaEntity.ROLE_OWNER.equals(member.getRole())) {
            throw new BusinessException(
                    "Only the group owner can do that", 403);
        }
    }

    private void applyShareRelevance(GroupSharedNoteJpaEntity note,
                                     StudyGroupJpaEntity group) {
        String subject = group.getSubject();
        if (subject == null || subject.isBlank()) {
            note.setRelevanceStatus("OK");
            return;
        }
        WikiPageJpaEntity page =
                wikiPageRepo.findById(note.getWikiPageId()).orElse(null);
        if (page == null) {
            throw new BusinessException("Wiki page not found", 404);
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
                    subject,
                    "Group: " + group.getName(),
                    sample);
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
            log.warn("[Groups] share relevance check failed, defaulting OK: {}",
                    e.getMessage());
            note.setRelevanceStatus("OK");
        }
    }

    private void ensureWithinCap(String userId) {
        if (isPilotUser(userId)) return;
        long current = memberRepo.countByUserId(userId);
        if (current >= FREE_GROUP_CAP) {
            throw new BusinessException(
                    "Free tier is capped at " + FREE_GROUP_CAP
                            + " group. Upgrade to join more.", 402);
        }
    }

    private boolean isPilotUser(String userId) {
        return flagRepo.findByUserIdAndFlagName(userId, PILOT_FLAG)
                .map(r -> r.isEnabled())
                .orElse(false);
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
