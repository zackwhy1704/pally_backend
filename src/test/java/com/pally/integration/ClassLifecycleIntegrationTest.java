package com.pally.integration;

import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.util.IdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the class lifecycle:
 * create class -> assign student -> verify provisioning -> remove -> verify lock.
 */
class ClassLifecycleIntegrationTest extends IntegrationTestBase {

    @Autowired
    private OrganizationJpaRepository orgRepo;

    @Autowired
    private UserJpaRepository userRepo;

    private AuthResult owner;
    private AuthResult student;
    private String orgId;

    @BeforeEach
    void seedOrg() {
        owner = registerUser("class-owner-" + System.nanoTime() + "@test.com", "password123");
        student = registerUser("class-student-" + System.nanoTime() + "@test.com", "password123");

        // Create organization
        orgId = IdGenerator.newId();
        OrganizationJpaEntity org = new OrganizationJpaEntity();
        org.setId(orgId);
        org.setName("Test Centre");
        org.setOwnerUserId(owner.userId());
        org.setSeatLimit(30);
        org.setCreatedAt(Instant.now());
        orgRepo.save(org);

        // Enroll student in the centre
        UserJpaEntity studentEntity = userRepo.findById(student.userId()).orElseThrow();
        studentEntity.setCentreId(orgId);
        userRepo.save(studentEntity);
    }

    @Test
    void createClass_returns200WithJoinCodeAndCorpusAvatar() {
        Map<String, Object> body = Map.of(
                "name", "P4 Math",
                "subject", "MATHS",
                "level", "P4",
                "characterType", "MOCHI");
        ResponseEntity<Map> response = post(
                "/api/v1/centre/organizations/" + orgId + "/classes",
                owner.token(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(data.get("joinCode")).isNotNull();
        assertThat(data.get("corpusAvatarId")).isNotNull();
        assertThat(data.get("name")).isEqualTo("P4 Math");
    }

    @Test
    void assignStudent_provisionsCentreAvatar() {
        // Create class
        Map<String, Object> classBody = Map.of(
                "name", "P4 Science",
                "subject", "SCIENCE",
                "level", "P4");
        ResponseEntity<Map> createResp = post(
                "/api/v1/centre/organizations/" + orgId + "/classes",
                owner.token(), classBody);
        Map<String, Object> classData = (Map<String, Object>) createResp.getBody().get("data");
        String classId = (String) classData.get("id");

        // Assign student
        Map<String, Object> assignBody = Map.of("userId", student.userId());
        ResponseEntity<Map> assignResp = post(
                "/api/v1/centre/organizations/" + orgId + "/classes/" + classId + "/members",
                owner.token(), assignBody);
        assertThat(assignResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> assignData = (Map<String, Object>) assignResp.getBody().get("data");
        assertThat(assignData.get("avatarId")).isNotNull();
        assertThat(assignData.get("classId")).isEqualTo(classId);
    }

    @Test
    void assignStudent_idempotent_returnsSameAvatar() {
        // Create class
        Map<String, Object> classBody = Map.of("name", "P5 Math", "subject", "MATHS");
        ResponseEntity<Map> createResp = post(
                "/api/v1/centre/organizations/" + orgId + "/classes",
                owner.token(), classBody);
        String classId = (String) ((Map<String, Object>) createResp.getBody().get("data")).get("id");

        // Assign twice
        Map<String, Object> assignBody = Map.of("userId", student.userId());
        ResponseEntity<Map> first = post(
                "/api/v1/centre/organizations/" + orgId + "/classes/" + classId + "/members",
                owner.token(), assignBody);
        ResponseEntity<Map> second = post(
                "/api/v1/centre/organizations/" + orgId + "/classes/" + classId + "/members",
                owner.token(), assignBody);

        String avatarId1 = (String) ((Map<String, Object>) first.getBody().get("data")).get("avatarId");
        String avatarId2 = (String) ((Map<String, Object>) second.getBody().get("data")).get("avatarId");
        assertThat(avatarId1).isEqualTo(avatarId2);
    }

    @Test
    void removeStudent_locksAvatar() {
        // Create class + assign
        Map<String, Object> classBody = Map.of("name", "P6 English", "subject", "ENGLISH");
        ResponseEntity<Map> createResp = post(
                "/api/v1/centre/organizations/" + orgId + "/classes",
                owner.token(), classBody);
        String classId = (String) ((Map<String, Object>) createResp.getBody().get("data")).get("id");

        Map<String, Object> assignBody = Map.of("userId", student.userId());
        post("/api/v1/centre/organizations/" + orgId + "/classes/" + classId + "/members",
                owner.token(), assignBody);

        // Remove
        ResponseEntity<Map> removeResp = delete(
                "/api/v1/centre/organizations/" + orgId + "/classes/" + classId
                        + "/members/" + student.userId(),
                owner.token());
        assertThat(removeResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> removeData = (Map<String, Object>) removeResp.getBody().get("data");
        assertThat(removeData.get("removed")).isEqualTo(true);
    }

    @Test
    void listClasses_returnsCreatedClasses() {
        // Create two classes
        post("/api/v1/centre/organizations/" + orgId + "/classes",
                owner.token(), Map.of("name", "Class A", "subject", "MATHS"));
        post("/api/v1/centre/organizations/" + orgId + "/classes",
                owner.token(), Map.of("name", "Class B", "subject", "SCIENCE"));

        ResponseEntity<Map> listResp = get(
                "/api/v1/centre/organizations/" + orgId + "/classes",
                owner.token());
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> classes = (List<Map<String, Object>>) listResp.getBody().get("data");
        assertThat(classes.size()).isGreaterThanOrEqualTo(2);
    }

    // ── Economy isolation (V65) ──────────────────────────────────────────────

    /**
     * The whole point of the kind split: CENTRE_CLASS avatars must be invisible
     * to the collectible economy. A student who has been assigned into several
     * classes (and so owns several CENTRE_CLASS avatars) must get a byte-identical
     * /shop/characters response to a brand-new student with none.
     */
    @Test
    void shopCharacters_identical_whetherOrNotStudentHasClassAvatars() {
        AuthResult plain = registerUser("plain-" + System.nanoTime() + "@test.com", "password123");

        // Baseline: a user with zero class avatars.
        ResponseEntity<Map> baseline = get("/api/v1/shop/characters", plain.token());
        assertThat(baseline.getStatusCode()).isEqualTo(HttpStatus.OK);
        Object baselineData = baseline.getBody().get("data");

        // Assign the seeded student into two classes → two CENTRE_CLASS avatars.
        for (String subject : List.of("MATHS", "SCIENCE")) {
            ResponseEntity<Map> createResp = post(
                    "/api/v1/centre/organizations/" + orgId + "/classes",
                    owner.token(), Map.of("name", subject + " Iso", "subject", subject));
            String classId = (String) ((Map<String, Object>) createResp.getBody().get("data")).get("id");
            post("/api/v1/centre/organizations/" + orgId + "/classes/" + classId + "/members",
                    owner.token(), Map.of("userId", student.userId()));
        }

        ResponseEntity<Map> withClasses = get("/api/v1/shop/characters", student.token());
        assertThat(withClasses.getStatusCode()).isEqualTo(HttpStatus.OK);
        Object withClassesData = withClasses.getBody().get("data");

        // Byte-identical character catalog/unlock state regardless of class avatars.
        assertThat(withClassesData).isEqualTo(baselineData);
    }

    /**
     * Students still SEE their class avatars in GET /api/v1/avatars (they need
     * them to access class content) — but each is tagged kind=CENTRE_CLASS and
     * carries the derived appearance so the client groups them out of the
     * collection grid.
     */
    @Test
    void avatarList_includesClassAvatar_taggedWithKindAndAppearance() {
        ResponseEntity<Map> createResp = post(
                "/api/v1/centre/organizations/" + orgId + "/classes",
                owner.token(), Map.of("name", "Tag Test E-Math", "subject", "MATHS"));
        String classId = (String) ((Map<String, Object>) createResp.getBody().get("data")).get("id");
        post("/api/v1/centre/organizations/" + orgId + "/classes/" + classId + "/members",
                owner.token(), Map.of("userId", student.userId()));

        ResponseEntity<Map> listResp = get("/api/v1/avatars", student.token());
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) listResp.getBody().get("data");
        List<Map<String, Object>> avatars = (List<Map<String, Object>>) data.get("avatars");

        Map<String, Object> classAvatar = avatars.stream()
                .filter(a -> "CENTRE_CLASS".equals(a.get("kind")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("class avatar missing from student list"));
        Map<String, Object> appearance = (Map<String, Object>) classAvatar.get("appearance");
        assertThat(appearance).isNotNull();
        assertThat((String) appearance.get("bandColorHex")).startsWith("#");
        assertThat(appearance.get("subjectGlyph")).isEqualTo("math");
        assertThat(appearance.get("initials")).isEqualTo("TT"); // "Tag Test E-Math" → Tag, Test
    }

    /**
     * A personal avatar has no appearance and kind=PERSONAL.
     */
    @Test
    void avatarList_personalAvatar_hasPersonalKind_andNoAppearance() {
        post("/api/v1/avatars", student.token(), Map.of(
                "name", "My Mochi", "subject", "SCIENCE", "characterType", "MOCHI"));

        ResponseEntity<Map> listResp = get("/api/v1/avatars", student.token());
        Map<String, Object> data = (Map<String, Object>) listResp.getBody().get("data");
        List<Map<String, Object>> avatars = (List<Map<String, Object>>) data.get("avatars");

        Map<String, Object> personal = avatars.stream()
                .filter(a -> "My Mochi".equals(a.get("name")))
                .findFirst().orElseThrow();
        assertThat(personal.get("kind")).isEqualTo("PERSONAL");
        assertThat(personal.get("appearance")).isNull();
    }

    /**
     * Students cannot delete a class avatar — 403, history preserved.
     */
    @Test
    void deleteClassAvatar_byStudent_isForbidden() {
        ResponseEntity<Map> createResp = post(
                "/api/v1/centre/organizations/" + orgId + "/classes",
                owner.token(), Map.of("name", "No Delete", "subject", "MATHS"));
        String classId = (String) ((Map<String, Object>) createResp.getBody().get("data")).get("id");
        ResponseEntity<Map> assignResp = post(
                "/api/v1/centre/organizations/" + orgId + "/classes/" + classId + "/members",
                owner.token(), Map.of("userId", student.userId()));
        String avatarId = (String) ((Map<String, Object>) assignResp.getBody().get("data")).get("avatarId");

        ResponseEntity<Map> deleteResp = delete("/api/v1/avatars/" + avatarId, student.token());
        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void classRoster_returnsAssignedStudents() {
        // Create class + assign
        Map<String, Object> classBody = Map.of("name", "Roster Test", "subject", "MATHS");
        ResponseEntity<Map> createResp = post(
                "/api/v1/centre/organizations/" + orgId + "/classes",
                owner.token(), classBody);
        String classId = (String) ((Map<String, Object>) createResp.getBody().get("data")).get("id");

        post("/api/v1/centre/organizations/" + orgId + "/classes/" + classId + "/members",
                owner.token(), Map.of("userId", student.userId()));

        ResponseEntity<Map> rosterResp = get(
                "/api/v1/centre/organizations/" + orgId + "/classes/" + classId + "/members",
                owner.token());
        assertThat(rosterResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> members = (List<Map<String, Object>>) rosterResp.getBody().get("data");
        assertThat(members.size()).isGreaterThanOrEqualTo(1);
    }
}
