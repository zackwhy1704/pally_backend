package com.pally.integration;

import com.pally.infrastructure.auth.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins App Store Guideline 1.2 on the Study Groups UGC surfaces.
 *
 * <p>THE GAP: students see content authored by other students — shared notes
 * (attributed by name, tapping through to the full note), member display names,
 * and group names. Guideline 1.2 requires BOTH a report mechanism and a block
 * mechanism. Reporting existed server-side ({@code group_reports}, V30); blocking
 * did not exist at all.
 *
 * <p>Owner-moderation was not a substitute: it makes the reporter wait on someone
 * else, and it cannot help in a CLASS group, where a student cannot leave
 * ({@code StudyGroupService} rejects leave with 403). A student in a class had no
 * way whatsoever to stop seeing a classmate's content.
 *
 * <p>These tests assert on the API RESPONSE, not on a widget tree. A client-side
 * filter would still ship the blocked student's content to the device, so a test
 * that only checked the UI would pass while the defect shipped.
 */
class UgcBlockAndReportTest extends IntegrationTestBase {

    @Autowired private JwtService jwt;

    private record Student(String id, String token) {}

    private Student newStudent(String name) {
        String id = newUserRow();
        jdbcTemplate.update("UPDATE users SET display_name = ? WHERE id = ?", name, id);
        return new Student(id, jwt.generateToken(id, "USER"));
    }

    /** Creates a group of the given type with both students as members. */
    private String seedGroup(String groupType, Student owner, Student other) {
        String groupId = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO study_groups (id, name, invite_code, created_by, group_type) "
                        + "VALUES (?, ?, ?, ?, ?)",
                groupId, "Revision Crew",
                ("JC" + System.nanoTime()).substring(0, 12), owner.id(), groupType);
        for (Student s : List.of(owner, other)) {
            jdbcTemplate.update(
                    "INSERT INTO group_members (group_id, user_id, role) VALUES (?, ?, ?)",
                    groupId, s.id(), s.id().equals(owner.id()) ? "OWNER" : "MEMBER");
        }
        return groupId;
    }

    /** A note shared into the group by {@code sharer}. */
    private String seedNote(String groupId, Student sharer, String title) {
        String noteId = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO group_shared_notes "
                        + "(id, group_id, wiki_page_id, title, shared_by, relevance_status) "
                        + "VALUES (?, ?, ?, ?, ?, 'OK')",
                noteId, groupId, java.util.UUID.randomUUID().toString(), title, sharer.id());
        return noteId;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> groupDetail(String groupId, Student viewer) {
        var body = get("/api/v1/groups/" + groupId, viewer.token()).getBody();
        return (Map<String, Object>) body.get("data");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> notesIn(Map<String, Object> detail) {
        return (List<Map<String, Object>>) detail.getOrDefault("sharedNotes", List.of());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> membersIn(Map<String, Object> detail) {
        return (List<Map<String, Object>>) detail.getOrDefault("members", List.of());
    }

    // ── the note must leave the RESPONSE, not just the widget tree ───────────

    @Test
    void blockedUsersNotes_areAbsentFromTheGroupDetailResponse() {
        Student alice = newStudent("Alice");
        Student bob = newStudent("Bob");
        String groupId = seedGroup("STUDY", alice, bob);
        seedNote(groupId, bob, "Bob's photosynthesis notes");

        assertThat(notesIn(groupDetail(groupId, alice)))
                .as("precondition: Alice can see Bob's note before blocking")
                .hasSize(1);

        post("/api/v1/blocks/" + bob.id(), alice.token(), Map.of());

        assertThat(notesIn(groupDetail(groupId, alice)))
                .as("a blocked student's note must never reach the device — "
                        + "filtering in the widget tree would still ship the content")
                .isEmpty();
    }

    @Test
    void blockedUser_isAbsentFromTheMemberList() {
        Student alice = newStudent("Alice");
        Student bob = newStudent("Bob");
        String groupId = seedGroup("STUDY", alice, bob);

        post("/api/v1/blocks/" + bob.id(), alice.token(), Map.of());

        assertThat(membersIn(groupDetail(groupId, alice)))
                .as("a display name is user-authored free text, so it is a UGC surface too")
                .noneMatch(m -> bob.id().equals(m.get("userId")));
    }

    // ── the case that actually needs blocking: a CLASS group ────────────────

    @Test
    void blockingWorksInsideAClassGroup_whereLeavingIsImpossible() {
        // THE POINT OF THE FEATURE. A student cannot leave a CLASS group, so
        // without blocking they had no way at all to stop seeing a classmate.
        Student alice = newStudent("Alice");
        Student bob = newStudent("Bob");
        String groupId = seedGroup("CLASS", alice, bob);
        seedNote(groupId, bob, "Bob's note in a class group");

        post("/api/v1/blocks/" + bob.id(), alice.token(), Map.of());

        assertThat(notesIn(groupDetail(groupId, alice))).isEmpty();
    }

    @Test
    void blocking_doesNotChangeMembership() {
        // Blocking must never kick or unenrol. The blocked student stays in the
        // class; the blocker simply stops seeing them.
        Student alice = newStudent("Alice");
        Student bob = newStudent("Bob");
        String groupId = seedGroup("CLASS", alice, bob);

        post("/api/v1/blocks/" + bob.id(), alice.token(), Map.of());

        Integer stillMember = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM group_members WHERE group_id = ? AND user_id = ?",
                Integer.class, groupId, bob.id());
        assertThat(stillMember)
                .as("a block must not remove anyone from a class they are enrolled in")
                .isEqualTo(1);
    }

    // ── reversible ──────────────────────────────────────────────────────────

    @Test
    void unblock_restoresVisibility() {
        // A 13-year-old who mis-taps must not permanently lose a classmate's notes.
        Student alice = newStudent("Alice");
        Student bob = newStudent("Bob");
        String groupId = seedGroup("STUDY", alice, bob);
        seedNote(groupId, bob, "Bob's note");

        post("/api/v1/blocks/" + bob.id(), alice.token(), Map.of());
        assertThat(notesIn(groupDetail(groupId, alice))).isEmpty();

        delete("/api/v1/blocks/" + bob.id(), alice.token());

        assertThat(notesIn(groupDetail(groupId, alice)))
                .as("blocking must be reversible, not a one-way trap")
                .hasSize(1);
    }

    @Test
    void theBlockList_isReadable_soAStudentCanSeeWhoTheyBlocked() {
        // Reversibility is useless if you cannot find who you blocked.
        Student alice = newStudent("Alice");
        Student bob = newStudent("Bob");
        post("/api/v1/blocks/" + bob.id(), alice.token(), Map.of());

        var body = get("/api/v1/blocks", alice.token()).getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocked = (List<Map<String, Object>>) body.get("data");

        assertThat(blocked).anyMatch(b -> bob.id().equals(b.get("userId"))
                && "Bob".equals(b.get("displayName")));
    }

    // ── one-directional ─────────────────────────────────────────────────────

    @Test
    void blockingIsOneDirectional_AblockingBDoesNotHideAFromB() {
        // Symmetric blocking would let one student silently remove themselves from
        // another's study group — a griefing vector, and not what a block means.
        Student alice = newStudent("Alice");
        Student bob = newStudent("Bob");
        String groupId = seedGroup("STUDY", alice, bob);
        seedNote(groupId, alice, "Alice's note");

        post("/api/v1/blocks/" + bob.id(), alice.token(), Map.of());

        assertThat(notesIn(groupDetail(groupId, bob)))
                .as("Bob must still see Alice — the block runs one way only")
                .hasSize(1);
        assertThat(membersIn(groupDetail(groupId, bob)))
                .anyMatch(m -> alice.id().equals(m.get("userId")));
    }

    @Test
    void selfBlock_isRefused() {
        // Would hide the student's OWN shared notes from themselves, with no
        // obvious way to work out why.
        Student alice = newStudent("Alice");

        var res = post("/api/v1/blocks/" + alice.id(), alice.token(), Map.of());

        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    // ── report reaches group_reports with the right target ───────────────────

    @Test
    void reportingANote_recordsTheNoteAsTheTarget() {
        Student alice = newStudent("Alice");
        Student bob = newStudent("Bob");
        String groupId = seedGroup("STUDY", alice, bob);
        String noteId = seedNote(groupId, bob, "Bob's note");

        post("/api/v1/groups/" + groupId + "/report", alice.token(),
                Map.of("targetNoteId", noteId, "reason", "OBJECTIONABLE"));

        Integer found = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM group_reports WHERE reporter_user_id = ? AND target_note_id = ?",
                Integer.class, alice.id(), noteId);
        assertThat(found).isEqualTo(1);
    }

    @Test
    void reportingAUser_recordsTheUserAsTheTarget() {
        Student alice = newStudent("Alice");
        Student bob = newStudent("Bob");
        String groupId = seedGroup("STUDY", alice, bob);

        post("/api/v1/groups/" + groupId + "/report", alice.token(),
                Map.of("targetUserId", bob.id(), "reason", "OBJECTIONABLE"));

        Integer found = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM group_reports WHERE reporter_user_id = ? AND target_user_id = ?",
                Integer.class, alice.id(), bob.id());
        assertThat(found).isEqualTo(1);
    }

    @Test
    void reportingDoesNotBlock_theyAreSeparateMechanisms() {
        // Guideline 1.2 requires each mechanism to EXIST. Bundling them would mean
        // a student who wants to flag something for review is forced to also stop
        // seeing a classmate they may still need to study with.
        Student alice = newStudent("Alice");
        Student bob = newStudent("Bob");
        String groupId = seedGroup("STUDY", alice, bob);
        seedNote(groupId, bob, "Bob's note");

        post("/api/v1/groups/" + groupId + "/report", alice.token(),
                Map.of("targetUserId", bob.id(), "reason", "OBJECTIONABLE"));

        assertThat(notesIn(groupDetail(groupId, alice)))
                .as("reporting alone must not hide content")
                .hasSize(1);
    }
}
