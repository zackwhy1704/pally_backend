package com.pally.api.centre;

import com.pally.domain.account.AccountType;

import com.pally.domain.centre.CentreAccessService;
import com.pally.domain.organization.ClassEnrollmentService;
import com.pally.infrastructure.persistence.avatar.AvatarJpaEntity;
import com.pally.infrastructure.persistence.avatar.AvatarJpaRepository;
import com.pally.infrastructure.persistence.organization.CentreEnrollCodeJpaRepository;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaEntity;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgClassJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgClassJpaRepository;
import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CentreControllerTest {

    @Mock CentreAccessService accessService;
    @Mock OrganizationJpaRepository orgRepo;
    @Mock CentreEnrollCodeJpaRepository codeRepo;
    @Mock OrgClassJpaRepository classRepo;
    @Mock ClassMembershipJpaRepository membershipRepo;
    @Mock ClassEnrollmentService classEnrollmentService;
    @Mock UserJpaRepository userRepo;
    @Mock QuizQuestionResultJpaRepository quizResultRepo;
    @Mock AvatarJpaRepository avatarJpaRepository;

    @InjectMocks CentreController controller;

    private static final String OWNER_ID  = "owner-1";
    private static final String ORG_ID    = "org-1";
    private static final String STUDENT_A = "student-a";
    private static final String AVATAR_ID = "avatar-1";

    private OrganizationJpaEntity org;

    @BeforeEach
    void setUp() {
        org = new OrganizationJpaEntity();
        org.setId(ORG_ID);
        org.setName("Test Centre");
        org.setOwnerUserId(OWNER_ID);
        org.setSeatLimit(30);
        org.setCreatedAt(Instant.now());

        lenient().when(accessService.ensureOwner(OWNER_ID, ORG_ID)).thenReturn(org);
    }

    // ── POST /redeem-class-code ──────────────────────────────────────────

    private OrgClassJpaEntity classEntity() {
        OrgClassJpaEntity cls = new OrgClassJpaEntity();
        cls.setId("class-1");
        cls.setOrganizationId(ORG_ID);
        cls.setName("P4 Math");
        cls.setJoinCode("ABCD2345");
        cls.setCharacterType("MOCHI");
        return cls;
    }

    @Test
    void redeemClassCode_joinsCentre_enrollsInClass_andReturnsClassInfo() {
        OrgClassJpaEntity cls = classEntity();
        when(classRepo.findByJoinCode("ABCD2345")).thenReturn(Optional.of(cls));
        UserJpaEntity user = new UserJpaEntity();
        user.setId(STUDENT_A);
        when(userRepo.findById(STUDENT_A)).thenReturn(Optional.of(user));
        when(classEnrollmentService.enroll(cls, STUDENT_A)).thenReturn(AVATAR_ID);

        ResponseEntity<ApiResponse<Map<String, Object>>> resp =
                controller.redeemClassCode(STUDENT_A, Map.of("code", "abcd2345"));

        assertThat(user.getCentreId()).isEqualTo(ORG_ID);
        assertThat(user.getCohortLabel()).isEqualTo("P4 Math");
        verify(classEnrollmentService).enroll(cls, STUDENT_A);
        Map<String, Object> data = resp.getBody().data();
        assertThat(data.get("classId")).isEqualTo("class-1");
        assertThat(data.get("className")).isEqualTo("P4 Math");
        assertThat(data.get("avatarId")).isEqualTo(AVATAR_ID);
    }

    @Test
    void redeemClassCode_unknownCode_is404_andNotEnrolled() {
        when(classRepo.findByJoinCode("ZZZZ9999")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                controller.redeemClassCode(STUDENT_A, Map.of("code", "ZZZZ9999")))
                .isInstanceOf(BusinessException.class);
        verify(classEnrollmentService, never()).enroll(any(), anyString());
    }

    @Test
    void redeemClassCode_blankCode_is400() {
        assertThatThrownBy(() ->
                controller.redeemClassCode(STUDENT_A, Map.of("code", "  ")))
                .isInstanceOf(BusinessException.class);
        verify(classEnrollmentService, never()).enroll(any(), anyString());
    }

    // ── POST /leave-class ────────────────────────────────────────────────

    @Test
    void leaveClass_lastClassInCentre_clearsCentreId() {
        OrgClassJpaEntity cls = classEntity(); // org = ORG_ID
        when(classRepo.findById("class-1")).thenReturn(Optional.of(cls));
        when(membershipRepo.findByUserId(STUDENT_A)).thenReturn(List.of()); // none left
        UserJpaEntity user = new UserJpaEntity();
        user.setId(STUDENT_A);
        user.setCentreId(ORG_ID);
        user.setCohortLabel("P4 Math");
        when(userRepo.findById(STUDENT_A)).thenReturn(Optional.of(user));

        var resp = controller.leaveClass(STUDENT_A, Map.of("classId", "class-1"));

        verify(classEnrollmentService).leave(cls, STUDENT_A);
        assertThat(user.getCentreId()).isNull();
        assertThat(user.getCohortLabel()).isNull();
        assertThat(resp.getBody().data().get("leftCentre")).isEqualTo(true);
    }

    @Test
    void leaveClass_otherClassInSameCentre_keepsCentreId() {
        OrgClassJpaEntity cls = classEntity();
        when(classRepo.findById("class-1")).thenReturn(Optional.of(cls));
        ClassMembershipJpaEntity other = new ClassMembershipJpaEntity();
        other.setClassId("class-2");
        other.setUserId(STUDENT_A);
        other.setStatus(ClassMembershipJpaEntity.STATUS_ACTIVE);
        when(membershipRepo.findByUserId(STUDENT_A)).thenReturn(List.of(other));
        OrgClassJpaEntity cls2 = new OrgClassJpaEntity();
        cls2.setId("class-2");
        cls2.setOrganizationId(ORG_ID); // same centre
        when(classRepo.findById("class-2")).thenReturn(Optional.of(cls2));

        var resp = controller.leaveClass(STUDENT_A, Map.of("classId", "class-1"));

        verify(classEnrollmentService).leave(cls, STUDENT_A);
        verify(userRepo, never()).save(any(UserJpaEntity.class));
        assertThat(resp.getBody().data().get("leftCentre")).isEqualTo(false);
    }

    @Test
    void leaveClass_blankClassId_is400() {
        assertThatThrownBy(() -> controller.leaveClass(STUDENT_A, Map.of("classId", "  ")))
                .isInstanceOf(BusinessException.class);
        verify(classEnrollmentService, never()).leave(any(), anyString());
    }

    // ── GET /me ──────────────────────────────────────────────────────────

    @Test
    void me_ownerWithNoStudents_returnsOrgDetails() {
        when(orgRepo.findFirstByOwnerUserId(OWNER_ID)).thenReturn(Optional.of(org));
        when(userRepo.countByCentreId(ORG_ID)).thenReturn(0L);
        when(userRepo.findByCentreId(ORG_ID)).thenReturn(Collections.emptyList());

        ResponseEntity<ApiResponse<Map<String, Object>>> response = controller.me(OWNER_ID);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> body = response.getBody().data();
        assertThat(body.get("orgId")).isEqualTo(ORG_ID);
        assertThat(body.get("orgName")).isEqualTo("Test Centre");
        assertThat(body.get("seatsUsed")).isEqualTo(0L);
        assertThat(body.get("seatLimit")).isEqualTo(30);
        assertThat(body.get("cohorts")).isEqualTo(List.of());
    }

    @Test
    void me_ownerWithStudents_returnsCohortsSorted() {
        when(orgRepo.findFirstByOwnerUserId(OWNER_ID)).thenReturn(Optional.of(org));
        when(userRepo.countByCentreId(ORG_ID)).thenReturn(3L);

        UserJpaEntity u1 = makeUser(STUDENT_A, "Sec3B");
        UserJpaEntity u2 = makeUser("student-b", "Sec3A");
        UserJpaEntity u3 = makeUser("student-c", "Sec3A");
        when(userRepo.findByCentreId(ORG_ID)).thenReturn(List.of(u1, u2, u3));

        var response = controller.me(OWNER_ID);
        Map<String, Object> body = response.getBody().data();
        assertThat(body.get("seatsUsed")).isEqualTo(3L);
        @SuppressWarnings("unchecked")
        List<String> cohorts = (List<String>) body.get("cohorts");
        assertThat(cohorts).containsExactly("Sec3A", "Sec3B"); // sorted
    }

    @Test
    void me_notOwner_throws403() {
        when(orgRepo.findFirstByOwnerUserId("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.me("nobody"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No centre access");
    }

    // ── POST /organizations/{orgId}/avatars/{avatarId}/mark-centre ───────

    @Test
    void markCentre_avatarInOrg_setsCentreAvatarTrue() {
        AvatarJpaEntity avatar = makeAvatar(AVATAR_ID, STUDENT_A);
        when(avatarJpaRepository.findById(AVATAR_ID)).thenReturn(Optional.of(avatar));

        UserJpaEntity student = makeUser(STUDENT_A, "Sec3A");
        student.setCentreId(ORG_ID);
        when(userRepo.findById(STUDENT_A)).thenReturn(Optional.of(student));

        var response = controller.markCentre(OWNER_ID, ORG_ID, AVATAR_ID);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> body = response.getBody().data();
        assertThat(body.get("avatarId")).isEqualTo(AVATAR_ID);
        assertThat(body.get("centreAvatar")).isEqualTo(true);
        verify(avatarJpaRepository).save(avatar);
        assertThat(avatar.isCentreAvatar()).isTrue();
    }

    @Test
    void markCentre_avatarNotFound_throws404() {
        when(avatarJpaRepository.findById("no-such")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.markCentre(OWNER_ID, ORG_ID, "no-such"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Avatar not found");
    }

    @Test
    void markCentre_avatarOwnerNotInOrg_throws403() {
        AvatarJpaEntity avatar = makeAvatar(AVATAR_ID, STUDENT_A);
        when(avatarJpaRepository.findById(AVATAR_ID)).thenReturn(Optional.of(avatar));

        UserJpaEntity student = makeUser(STUDENT_A, "Sec3A");
        student.setCentreId("other-org"); // different org
        when(userRepo.findById(STUDENT_A)).thenReturn(Optional.of(student));

        assertThatThrownBy(() -> controller.markCentre(OWNER_ID, ORG_ID, AVATAR_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not in this org");
    }

    @Test
    void markCentre_nonOwner_throws403ViaAccessService() {
        when(accessService.ensureOwner("intruder", ORG_ID))
                .thenThrow(new BusinessException("You don't own this organization", 403));

        assertThatThrownBy(() -> controller.markCentre("intruder", ORG_ID, AVATAR_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("don't own");
    }

    // ── GET /organizations/{orgId}/activity ─────────────────────────────

    @Test
    void activity_returnsCountAndSince() {
        when(quizResultRepo.countResultsForCentreSince(anyString(), any())).thenReturn(42L);

        var response = controller.activity(OWNER_ID, ORG_ID, "2026-06-01T00:00:00Z");

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> body = response.getBody().data();
        assertThat(body.get("quizResultCount")).isEqualTo(42L);
        assertThat(body.get("activeSince")).isNotNull();
    }

    @Test
    void activity_invalidSinceParam_defaultsToSevenDays() {
        when(quizResultRepo.countResultsForCentreSince(anyString(), any())).thenReturn(5L);

        var response = controller.activity(OWNER_ID, ORG_ID, "not-a-date");

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody().data().get("quizResultCount")).isEqualTo(5L);
    }

    @Test
    void activity_missingSinceParam_defaultsToSevenDays() {
        when(quizResultRepo.countResultsForCentreSince(anyString(), any())).thenReturn(0L);

        var response = controller.activity(OWNER_ID, ORG_ID, null);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
    }

    @Test
    void activity_nonOwner_throws403() {
        when(accessService.ensureOwner("intruder", ORG_ID))
                .thenThrow(new BusinessException("You don't own this organization", 403));

        assertThatThrownBy(() -> controller.activity("intruder", ORG_ID, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("don't own");
    }

    // ── Self-serve onboarding ────────────────────────────────────────────

    @Test
    void onboard_createsOrg_whenCallerOwnsNone_seatingThemAsOwner() {
        when(orgRepo.findFirstByOwnerUserId("u-new")).thenReturn(Optional.empty());
        UserJpaEntity user = new UserJpaEntity();
        user.setId("u-new");
        when(userRepo.findById("u-new")).thenReturn(Optional.of(user));
        when(orgRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<ApiResponse<Map<String, Object>>> resp =
                controller.onboard("u-new", Map.of("centreName", "ABC Learning"));

        Map<String, Object> body = resp.getBody().data();
        assertThat(body.get("orgName")).isEqualTo("ABC Learning");
        assertThat(body.get("alreadyOwned")).isEqualTo(false);
        assertThat(body.get("orgId")).isNotNull();
        // Ownership is recorded on the org; we must NOT overflow the VARCHAR(10)
        // account_type column, and the owner is not seated as a student.
        verify(orgRepo).save(any(OrganizationJpaEntity.class));
        assertThat(user.getAccountType()).isEqualTo(AccountType.SOLO); // unchanged
        assertThat(user.getCentreId()).isNull();
    }

    @Test
    void onboard_isIdempotent_whenCallerAlreadyOwnsOrg() {
        OrganizationJpaEntity existing = new OrganizationJpaEntity();
        existing.setId("org-x");
        existing.setName("Existing Centre");
        existing.setOwnerUserId("u1");
        when(orgRepo.findFirstByOwnerUserId("u1")).thenReturn(Optional.of(existing));

        ResponseEntity<ApiResponse<Map<String, Object>>> resp =
                controller.onboard("u1", Map.of("centreName", "Ignored"));

        Map<String, Object> body = resp.getBody().data();
        assertThat(body.get("orgId")).isEqualTo("org-x");
        assertThat(body.get("alreadyOwned")).isEqualTo(true);
        verify(orgRepo, never()).save(any());
    }

    @Test
    void onboard_blankName_isRejected() {
        when(orgRepo.findFirstByOwnerUserId("u2")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.onboard("u2", Map.of("centreName", "  ")))
                .isInstanceOf(BusinessException.class);
        verify(orgRepo, never()).save(any());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private UserJpaEntity makeUser(String id, String cohort) {
        UserJpaEntity u = new UserJpaEntity();
        u.setId(id);
        u.setDisplayName("Student " + id);
        u.setCohortLabel(cohort);
        u.setCentreId(ORG_ID);
        return u;
    }

    private AvatarJpaEntity makeAvatar(String id, String userId) {
        AvatarJpaEntity a = new AvatarJpaEntity();
        a.setId(id);
        a.setUserId(userId);
        a.setName("Test Mochi");
        return a;
    }
}
