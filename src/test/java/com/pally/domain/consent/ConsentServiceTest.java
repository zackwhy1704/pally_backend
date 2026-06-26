package com.pally.domain.consent;

import com.pally.domain.subscription.PremiumService;
import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.email.EmailService;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ConsentService}.
 * No Spring context — just Mockito stubs and pure domain logic assertions.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConsentServiceTest {

    @Mock ConsentRepository consentRepository;
    @Mock UserRepository    userRepository;
    @Mock PremiumService    premiumService;
    @Mock EmailService      emailService;

    @InjectMocks ConsentService service;

    private static final String USER_ID   = "user-abc";
    private static final String PARENT_ID = "parent-xyz";

    // ── helpers ────────────────────────────────────────────────────────────

    private User activeUser() {
        User u = new User();
        u.setId(USER_ID);
        u.setAccountStatus("ACTIVE");
        u.setParentId(PARENT_ID);
        return u;
    }

    private ConsentRepository.ConsentRequest pendingRequest() {
        // createdAt 2 minutes ago → past the resend cooldown, so a resend succeeds.
        Instant created = Instant.now().minusSeconds(120);
        return new ConsentRepository.ConsentRequest(
                "req-1", USER_ID, "parent@example.com", "token123",
                ConsentRepository.ConsentRequest.STATUS_PENDING,
                created, created.plusSeconds(7 * 24 * 3600), null
        );
    }

    @Test
    void resendParentConsent_withinCooldown_throws429() {
        Instant now = Instant.now(); // fresh request → cooldown active
        when(consentRepository.findLatestRequestByChildUserIdAndStatus(
                USER_ID, ConsentRepository.ConsentRequest.STATUS_PENDING))
                .thenReturn(Optional.of(new ConsentRepository.ConsentRequest(
                        "r", USER_ID, "parent@example.com", "tok",
                        ConsentRepository.ConsentRequest.STATUS_PENDING,
                        now, now.plusSeconds(600), null)));

        assertThatThrownBy(() -> service.resendParentConsent(USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getHttpStatus()).isEqualTo(429));
    }

    @Test
    void maskEmail_masksLocalPart() {
        assertThat(ConsentService.maskEmail("john@gmail.com")).isEqualTo("j***@gmail.com");
        assertThat(ConsentService.maskEmail(null)).isNull();
    }

    private ConsentRepository.ConsentRequest approvedRequest() {
        Instant now = Instant.now();
        return new ConsentRepository.ConsentRequest(
                "req-2", USER_ID, "parent@example.com", "token456",
                ConsentRepository.ConsentRequest.STATUS_APPROVED,
                now.minusSeconds(3600), now.plusSeconds(7 * 24 * 3600), now
        );
    }

    // ══════════════════════════════════════════════════════════════════════
    // getStatus
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void getStatus_userNotFound_throws404() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStatus(USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void getStatus_activeUserNoConsents_returnsAccountStatusAndAiTransferFalse() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(activeUser()));
        when(consentRepository.hasConsentForPurpose(USER_ID, ConsentGuard.REASON_AI_DATA_TRANSFER))
                .thenReturn(false);
        when(consentRepository.findLatestRequestByChildUserIdAndStatus(
                USER_ID, ConsentRepository.ConsentRequest.STATUS_PENDING))
                .thenReturn(Optional.empty());
        when(consentRepository.findLatestRequestByChildUserIdAndStatus(
                USER_ID, ConsentRepository.ConsentRequest.STATUS_APPROVED))
                .thenReturn(Optional.empty());

        Map<String, Object> result = service.getStatus(USER_ID);

        assertThat(result.get("accountStatus")).isEqualTo("ACTIVE");
        assertThat(result.get("aiDataTransfer")).isEqualTo(false);
        assertThat(result).doesNotContainKey("pendingRequest");
        assertThat(result).doesNotContainKey("approvedAt");
    }

    @Test
    void getStatus_hasPendingRequest_includesPendingRequestFields() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(activeUser()));
        when(consentRepository.hasConsentForPurpose(anyString(), anyString())).thenReturn(false);
        when(consentRepository.findLatestRequestByChildUserIdAndStatus(
                USER_ID, ConsentRepository.ConsentRequest.STATUS_PENDING))
                .thenReturn(Optional.of(pendingRequest()));
        when(consentRepository.findLatestRequestByChildUserIdAndStatus(
                USER_ID, ConsentRepository.ConsentRequest.STATUS_APPROVED))
                .thenReturn(Optional.empty());

        Map<String, Object> result = service.getStatus(USER_ID);

        assertThat(result).containsKey("pendingRequest");
        @SuppressWarnings("unchecked")
        Map<String, Object> pending = (Map<String, Object>) result.get("pendingRequest");
        assertThat(pending.get("parentEmail")).isEqualTo("parent@example.com");
        assertThat(pending.get("expiresAt")).isNotNull();
    }

    @Test
    void getStatus_hasApprovedRequest_includesApprovedAt() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(activeUser()));
        when(consentRepository.hasConsentForPurpose(anyString(), anyString())).thenReturn(false);
        when(consentRepository.findLatestRequestByChildUserIdAndStatus(
                USER_ID, ConsentRepository.ConsentRequest.STATUS_PENDING))
                .thenReturn(Optional.empty());
        when(consentRepository.findLatestRequestByChildUserIdAndStatus(
                USER_ID, ConsentRepository.ConsentRequest.STATUS_APPROVED))
                .thenReturn(Optional.of(approvedRequest()));

        Map<String, Object> result = service.getStatus(USER_ID);

        assertThat(result).containsKey("approvedAt");
        assertThat(result.get("approvedAt")).isNotNull();
    }

    @Test
    void getStatus_aiDataTransferConsented_returnsAiTransferTrue() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(activeUser()));
        when(consentRepository.hasConsentForPurpose(USER_ID, ConsentGuard.REASON_AI_DATA_TRANSFER))
                .thenReturn(true);
        when(consentRepository.findLatestRequestByChildUserIdAndStatus(anyString(), anyString()))
                .thenReturn(Optional.empty());

        Map<String, Object> result = service.getStatus(USER_ID);

        assertThat(result.get("aiDataTransfer")).isEqualTo(true);
    }

    // ══════════════════════════════════════════════════════════════════════
    // requestParentConsent
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void requestParentConsent_validEmail_savesRequestAndReturnsPending() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(activeUser()));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(consentRepository.saveRequest(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = service.requestParentConsent(USER_ID, "Parent@Example.COM");

        assertThat(result.get("status")).isEqualTo("PENDING");
        assertThat(result.get("parentEmail")).isEqualTo("parent@example.com");
        assertThat(result.get("expiresAt")).isNotNull();
        verify(consentRepository).saveRequest(any());
    }

    @Test
    void requestParentConsent_emailIsNormalizedToLowercase() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(activeUser()));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(consentRepository.saveRequest(any())).thenAnswer(inv -> inv.getArgument(0));

        service.requestParentConsent(USER_ID, "  UPPER@TEST.COM  ");

        ArgumentCaptor<ConsentRepository.ConsentRequest> captor =
                ArgumentCaptor.forClass(ConsentRepository.ConsentRequest.class);
        verify(consentRepository).saveRequest(captor.capture());
        assertThat(captor.getValue().parentEmail()).isEqualTo("upper@test.com");
    }

    @Test
    void requestParentConsent_setsAccountStatusToPendingConsent() {
        User user = activeUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(consentRepository.saveRequest(any())).thenAnswer(inv -> inv.getArgument(0));

        service.requestParentConsent(USER_ID, "parent@test.com");

        verify(userRepository).save(any(User.class));
        assertThat(user.getAccountStatus()).isEqualTo(ConsentGuard.STATUS_PENDING);
    }

    @Test
    void requestParentConsent_nullEmail_throws400() {
        assertThatThrownBy(() -> service.requestParentConsent(USER_ID, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("valid parent email");
    }

    @Test
    void requestParentConsent_blankEmail_throws400() {
        assertThatThrownBy(() -> service.requestParentConsent(USER_ID, "   "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("valid parent email");
    }

    @Test
    void requestParentConsent_emailMissingAtSign_throws400() {
        assertThatThrownBy(() -> service.requestParentConsent(USER_ID, "notanemail"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("valid parent email");
    }

    @Test
    void requestParentConsent_sendsConsentEmailToParent() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(activeUser()));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(consentRepository.saveRequest(any())).thenAnswer(inv -> inv.getArgument(0));
        when(emailService.isConfigured()).thenReturn(true);

        service.requestParentConsent(USER_ID, "parent@example.com");

        verify(emailService).sendHtml(
                eq("parent@example.com"),
                contains("approve"),
                anyString());
    }

    // ══════════════════════════════════════════════════════════════════════
    // resendParentConsent
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void resendParentConsent_pendingRequestExists_resendSucceeds() {
        when(consentRepository.findLatestRequestByChildUserIdAndStatus(
                USER_ID, ConsentRepository.ConsentRequest.STATUS_PENDING))
                .thenReturn(Optional.of(pendingRequest()));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(activeUser()));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(consentRepository.saveRequest(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = service.resendParentConsent(USER_ID);

        assertThat(result.get("status")).isEqualTo("PENDING");
        assertThat(result.get("parentEmail")).isEqualTo("parent@example.com");
    }

    @Test
    void resendParentConsent_noPendingRequest_throws404() {
        when(consentRepository.findLatestRequestByChildUserIdAndStatus(
                USER_ID, ConsentRepository.ConsentRequest.STATUS_PENDING))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resendParentConsent(USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No pending consent request");
    }

    // ══════════════════════════════════════════════════════════════════════
    // approveParentConsent
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void approveParentConsent_validToken_approvesAndActivatesChildAccount() {
        ConsentRepository.ConsentRequest req = pendingRequest();
        when(consentRepository.findRequestByToken("token123")).thenReturn(Optional.of(req));
        when(consentRepository.saveRequest(any())).thenAnswer(inv -> inv.getArgument(0));
        User child = activeUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(child));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = service.approveParentConsent("token123");

        assertThat(result.get("status")).isEqualTo("APPROVED");
        assertThat(result.get("childUserId")).isEqualTo(USER_ID);
        verify(premiumService).grantTrial(USER_ID);
        verify(consentRepository).saveRecord(any());
    }

    @Test
    void approveParentConsent_setsChildAccountToActive() {
        ConsentRepository.ConsentRequest req = pendingRequest();
        when(consentRepository.findRequestByToken("token123")).thenReturn(Optional.of(req));
        when(consentRepository.saveRequest(any())).thenAnswer(inv -> inv.getArgument(0));
        User child = activeUser();
        child.setAccountStatus(ConsentGuard.STATUS_PENDING);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(child));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.approveParentConsent("token123");

        assertThat(child.getAccountStatus()).isEqualTo(ConsentGuard.STATUS_ACTIVE);
    }

    @Test
    void approveParentConsent_invalidToken_throws404() {
        when(consentRepository.findRequestByToken("bad-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approveParentConsent("bad-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid or expired");
    }

    @Test
    void approveParentConsent_alreadyApprovedToken_throws400() {
        ConsentRepository.ConsentRequest approved = approvedRequest();
        when(consentRepository.findRequestByToken("token456")).thenReturn(Optional.of(approved));

        assertThatThrownBy(() -> service.approveParentConsent("token456"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already been used");
    }

    @Test
    void approveParentConsent_expiredToken_expiresRequestAndThrows400() {
        Instant past = Instant.now().minusSeconds(1);
        ConsentRepository.ConsentRequest expired = new ConsentRepository.ConsentRequest(
                "req-3", USER_ID, "parent@example.com", "token-expired",
                ConsentRepository.ConsentRequest.STATUS_PENDING,
                past.minusSeconds(7 * 24 * 3600), past, null
        );
        when(consentRepository.findRequestByToken("token-expired"))
                .thenReturn(Optional.of(expired));
        when(consentRepository.saveRequest(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.approveParentConsent("token-expired"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("expired");

        // Must flip request to EXPIRED status before throwing
        ArgumentCaptor<ConsentRepository.ConsentRequest> captor =
                ArgumentCaptor.forClass(ConsentRepository.ConsentRequest.class);
        verify(consentRepository).saveRequest(captor.capture());
        assertThat(captor.getValue().status())
                .isEqualTo(ConsentRepository.ConsentRequest.STATUS_EXPIRED);

        // Must NOT grant trial or write an audit record
        verify(premiumService, never()).grantTrial(anyString());
        verify(consentRepository, never()).saveRecord(any());
    }

    // ══════════════════════════════════════════════════════════════════════
    // recordSelfConsent
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void recordSelfConsent_savesRecordWithSelfConsenterAndCheckboxMethod() {
        when(consentRepository.saveRecord(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordSelfConsent(USER_ID);

        ArgumentCaptor<ConsentRepository.ConsentRecord> captor =
                ArgumentCaptor.forClass(ConsentRepository.ConsentRecord.class);
        verify(consentRepository).saveRecord(captor.capture());
        ConsentRepository.ConsentRecord record = captor.getValue();
        assertThat(record.userId()).isEqualTo(USER_ID);
        assertThat(record.consenter()).isEqualTo("SELF");
        assertThat(record.method()).isEqualTo("CHECKBOX");
        assertThat(record.purposes()).contains("tutoring");
        assertThat(record.policyVersion()).isEqualTo("2025-06-01");
    }

    // ══════════════════════════════════════════════════════════════════════
    // recordFamilyAcceptConsent
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void recordFamilyAcceptConsent_validParent_savesParentVisibilityRecord() {
        User child = activeUser(); // parentId = PARENT_ID
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(child));
        when(consentRepository.saveRecord(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordFamilyAcceptConsent(USER_ID, PARENT_ID);

        ArgumentCaptor<ConsentRepository.ConsentRecord> captor =
                ArgumentCaptor.forClass(ConsentRepository.ConsentRecord.class);
        verify(consentRepository).saveRecord(captor.capture());
        ConsentRepository.ConsentRecord record = captor.getValue();
        assertThat(record.userId()).isEqualTo(USER_ID);
        assertThat(record.childId()).isEqualTo(USER_ID);
        assertThat(record.parentId()).isEqualTo(PARENT_ID);
        assertThat(record.purposes()).contains("parent_visibility");
    }

    @Test
    void recordFamilyAcceptConsent_nullParentId_throws400() {
        assertThatThrownBy(() -> service.recordFamilyAcceptConsent(USER_ID, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("parentId is required");
    }

    @Test
    void recordFamilyAcceptConsent_blankParentId_throws400() {
        assertThatThrownBy(() -> service.recordFamilyAcceptConsent(USER_ID, "  "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("parentId is required");
    }

    @Test
    void recordFamilyAcceptConsent_userNotFound_throws404() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordFamilyAcceptConsent(USER_ID, PARENT_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void recordFamilyAcceptConsent_wrongParent_throws403() {
        User child = activeUser();
        child.setParentId("different-parent");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(child));

        assertThatThrownBy(() -> service.recordFamilyAcceptConsent(USER_ID, PARENT_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not your parent");
    }

    // ══════════════════════════════════════════════════════════════════════
    // recordAiDataTransferConsent
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void recordAiDataTransferConsent_savesRecordWithAiDisclosureMethodAndAiPurpose() {
        when(consentRepository.saveRecord(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordAiDataTransferConsent(USER_ID);

        ArgumentCaptor<ConsentRepository.ConsentRecord> captor =
                ArgumentCaptor.forClass(ConsentRepository.ConsentRecord.class);
        verify(consentRepository).saveRecord(captor.capture());
        ConsentRepository.ConsentRecord record = captor.getValue();
        assertThat(record.userId()).isEqualTo(USER_ID);
        assertThat(record.method()).isEqualTo("AI_DISCLOSURE");
        assertThat(record.purposes()).contains("AI_DATA_TRANSFER");
        assertThat(record.consenter()).isEqualTo("SELF");
        assertThat(record.policyVersion()).isEqualTo("2025-06-01");
    }
}
