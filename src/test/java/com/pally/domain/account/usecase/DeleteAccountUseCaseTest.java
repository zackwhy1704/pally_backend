package com.pally.domain.account.usecase;

import com.pally.domain.subscription.PremiumService;
import com.pally.infrastructure.persistence.activity.ActivityLogJpaRepository;
import com.pally.infrastructure.persistence.auth.BiometricChallengeJpaRepository;
import com.pally.infrastructure.persistence.auth.BiometricRegistrationJpaRepository;
import com.pally.infrastructure.persistence.auth.EmailVerificationTokenJpaRepository;
import com.pally.infrastructure.persistence.auth.RevokedTokenJpaEntity;
import com.pally.infrastructure.persistence.auth.RevokedTokenJpaRepository;
import com.pally.infrastructure.persistence.avatar.AvatarJpaEntity;
import com.pally.infrastructure.persistence.avatar.AvatarJpaRepository;
import com.pally.infrastructure.persistence.badge.UserBadgeJpaRepository;
import com.pally.infrastructure.persistence.chat.ChatMessageJpaRepository;
import com.pally.infrastructure.persistence.chat.ChatSessionJpaRepository;
import com.pally.infrastructure.persistence.chat.ChatSessionSummaryJpaRepository;
import com.pally.infrastructure.persistence.chat.HintTreeJpaRepository;
import com.pally.infrastructure.persistence.consent.ConsentRecordJpaRepository;
import com.pally.infrastructure.persistence.consent.ConsentRequestJpaRepository;
import com.pally.infrastructure.persistence.knowledge.KnowledgeFileJpaEntity;
import com.pally.infrastructure.persistence.knowledge.KnowledgeFileJpaRepository;
import com.pally.infrastructure.persistence.knowledge.WikiPageJpaRepository;
import com.pally.infrastructure.persistence.knowledge.WikiPageSourceJpaRepository;
import com.pally.infrastructure.persistence.mochi.UserMochiJpaRepository;
import com.pally.infrastructure.persistence.powerup.UserPowerupJpaRepository;
import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.persistence.progress.StudyPlanCompletionJpaRepository;
import com.pally.infrastructure.persistence.quiz.FlashcardJpaRepository;
import com.pally.infrastructure.persistence.quiz.QuizAnswerRecordJpaRepository;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaRepository;
import com.pally.infrastructure.persistence.referral.ReferralJpaRepository;
import com.pally.infrastructure.persistence.safety.ChatSafetyFlagJpaRepository;
import com.pally.infrastructure.persistence.shop.CharacterUnlockJpaRepository;
import com.pally.infrastructure.persistence.subscription.SubscriptionJpaRepository;
import com.pally.infrastructure.storage.StorageService;
import com.pally.infrastructure.stripe.StripeService;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DeleteAccountUseCase.
 *
 * <p>Invariants verified:
 * - Solo user with avatars/wiki/chat → all rows deleted, user row gone
 * - Parent with linked children → throws 409, no data deleted
 * - Stripe failure → account still deleted (failure logged, not thrown)
 * - Token revocation row is saved when jti is provided
 */
@ExtendWith(MockitoExtension.class)
class DeleteAccountUseCaseTest {

    // ── All repos mocked ──────────────────────────────────────────────────

    @Mock UserRepository userRepo;
    @Mock AvatarJpaRepository avatarRepo;
    @Mock ChatMessageJpaRepository chatMessageRepo;
    @Mock ChatSessionJpaRepository chatSessionRepo;
    @Mock ChatSessionSummaryJpaRepository chatSessionSummaryRepo;
    @Mock HintTreeJpaRepository hintTreeRepo;
    @Mock ChatSafetyFlagJpaRepository chatSafetyFlagRepo;
    @Mock FlashcardJpaRepository flashcardRepo;
    @Mock QuizAnswerRecordJpaRepository quizAnswerRepo;
    @Mock QuizQuestionResultJpaRepository quizQuestionResultRepo;
    @Mock WikiPageSourceJpaRepository wikiPageSourceRepo;
    @Mock WikiPageJpaRepository wikiPageRepo;
    @Mock KnowledgeFileJpaRepository knowledgeFileRepo;
    @Mock ActivityLogJpaRepository activityLogRepo;
    @Mock StudyPlanCompletionJpaRepository studyPlanCompletionRepo;
    @Mock UserBadgeJpaRepository badgeRepo;
    @Mock ConsentRecordJpaRepository consentRecordRepo;
    @Mock ConsentRequestJpaRepository consentRequestRepo;
    @Mock ReferralJpaRepository referralRepo;
    @Mock CharacterUnlockJpaRepository characterUnlockRepo;
    @Mock UserMochiJpaRepository userMochiRepo;
    @Mock UserPowerupJpaRepository userPowerupRepo;
    @Mock BiometricRegistrationJpaRepository biometricRegistrationRepo;
    @Mock BiometricChallengeJpaRepository biometricChallengeRepo;
    @Mock EmailVerificationTokenJpaRepository emailVerificationTokenRepo;
    @Mock SubscriptionJpaRepository subscriptionRepo;
    @Mock com.pally.infrastructure.persistence.star.StarAwardLogJpaRepository starAwardLogRepo;
    @Mock com.pally.infrastructure.persistence.assignment.AssignmentJpaRepository assignmentRepo;
    @Mock StorageService storageService;
    @Mock StripeService stripeService;
    @Mock PremiumService premiumService;
    @Mock RevokedTokenJpaRepository revokedTokenRepo;

    DeleteAccountUseCase useCase;

    private static final String USER_ID    = "user-alice";
    private static final String AVATAR_ID  = "avatar-math";
    private static final String JTI        = "jti-abc-123";
    private static final Instant EXPIRY    = Instant.now().plusSeconds(3600);

    @BeforeEach
    void setUp() {
        useCase = new DeleteAccountUseCase(
                userRepo, avatarRepo, chatMessageRepo, chatSessionRepo,
                chatSessionSummaryRepo, hintTreeRepo, chatSafetyFlagRepo,
                flashcardRepo, quizAnswerRepo, quizQuestionResultRepo,
                wikiPageSourceRepo, wikiPageRepo, knowledgeFileRepo,
                activityLogRepo, studyPlanCompletionRepo, badgeRepo,
                consentRecordRepo, consentRequestRepo, referralRepo,
                characterUnlockRepo, userMochiRepo, userPowerupRepo,
                biometricRegistrationRepo, biometricChallengeRepo,
                emailVerificationTokenRepo, subscriptionRepo,
                starAwardLogRepo, assignmentRepo,
                storageService, stripeService, premiumService, revokedTokenRepo
        );
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void stubNoChildren() {
        when(userRepo.countByParentId(USER_ID)).thenReturn(0);
    }

    private void stubEmptyLists() {
        when(avatarRepo.findByUserId(USER_ID)).thenReturn(List.of());
        when(referralRepo.findAll()).thenReturn(List.of());
        when(badgeRepo.findByUserId(USER_ID)).thenReturn(List.of());
        when(consentRecordRepo.findAll()).thenReturn(List.of());
        when(consentRequestRepo.findAll()).thenReturn(List.of());
        when(characterUnlockRepo.findAll()).thenReturn(List.of());
        when(userMochiRepo.findById_UserId(USER_ID)).thenReturn(List.of());
        when(userPowerupRepo.findById_UserId(USER_ID)).thenReturn(List.of());
        when(biometricRegistrationRepo.findByUserId(USER_ID)).thenReturn(List.of());
        when(biometricChallengeRepo.findByUserId(USER_ID)).thenReturn(List.of());
        when(emailVerificationTokenRepo.findByUserId(USER_ID)).thenReturn(List.of());
        when(subscriptionRepo.findById(USER_ID)).thenReturn(Optional.empty());
    }

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test
    void execute_clearsRestrictFkRows_beforeUserDelete_soDeleteCannotHalfAbort() {
        stubNoChildren();
        stubEmptyLists();

        useCase.execute(USER_ID, JTI, EXPIRY);

        // star_award_log.(parent_id|child_id) and assignment.student_id are FKs to users
        // with no ON DELETE — leaving them would abort the whole delete. They MUST be cleared.
        var inOrder = inOrder(starAwardLogRepo, assignmentRepo, userRepo);
        inOrder.verify(starAwardLogRepo).deleteByParticipant(USER_ID);
        inOrder.verify(assignmentRepo).deleteByStudentId(USER_ID);
        inOrder.verify(userRepo).deleteById(USER_ID); // user row deleted LAST
    }

    @Test
    void execute_soloUser_deletesUserRowAndRevokesToken() {
        stubNoChildren();
        stubEmptyLists();

        useCase.execute(USER_ID, JTI, EXPIRY);

        verify(userRepo).deleteById(USER_ID);
        ArgumentCaptor<RevokedTokenJpaEntity> captor =
                ArgumentCaptor.forClass(RevokedTokenJpaEntity.class);
        verify(revokedTokenRepo).save(captor.capture());
        assertThat(captor.getValue().getJti()).isEqualTo(JTI);
        assertThat(captor.getValue().getExpiresAt()).isEqualTo(EXPIRY);
    }

    @Test
    void execute_parentWithChildren_throws409AndDeletesNothing() {
        when(userRepo.countByParentId(USER_ID)).thenReturn(2);

        assertThatThrownBy(() -> useCase.execute(USER_ID, JTI, EXPIRY))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("unlink all child accounts");

        verify(userRepo, never()).deleteById(any());
        verify(subscriptionRepo, never()).deleteById(any());
        verifyNoInteractions(stripeService);
    }

    @Test
    void execute_stripeFailure_accountStillDeleted() {
        stubNoChildren();
        stubEmptyLists();

        // Subscription exists with a stripe subscription id
        var sub = new com.pally.infrastructure.persistence.subscription.SubscriptionJpaEntity();
        sub.setStripeSubscriptionId("sub_abc123");
        when(subscriptionRepo.findById(USER_ID)).thenReturn(Optional.of(sub));
        // Stripe throws
        doThrow(new RuntimeException("Stripe network error"))
                .when(stripeService).cancelSubscriptionForUser("sub_abc123");

        // Should NOT throw
        useCase.execute(USER_ID, JTI, EXPIRY);

        // User row is still deleted despite Stripe failure
        verify(userRepo).deleteById(USER_ID);
    }

    @Test
    void execute_withAvatar_deletesAllAvatarData() {
        stubNoChildren();

        AvatarJpaEntity avatar = new AvatarJpaEntity();
        avatar.setId(AVATAR_ID);
        avatar.setUserId(USER_ID);
        when(avatarRepo.findByUserId(USER_ID)).thenReturn(List.of(avatar));

        // Avatar data stubs
        when(chatMessageRepo.findAll()).thenReturn(List.of());
        when(chatSessionRepo.findAll()).thenReturn(List.of());
        when(chatSessionSummaryRepo.findAll()).thenReturn(List.of());
        when(chatSafetyFlagRepo.findAll()).thenReturn(List.of());
        when(hintTreeRepo.findAll()).thenReturn(List.of());
        when(flashcardRepo.findByAvatarId(AVATAR_ID)).thenReturn(List.of());
        when(quizAnswerRepo.findAll()).thenReturn(List.of());
        when(quizQuestionResultRepo.findAll()).thenReturn(List.of());
        when(wikiPageRepo.findByAvatarId(AVATAR_ID)).thenReturn(List.of());

        // Stub a knowledge file with a storage key
        KnowledgeFileJpaEntity kfEntity = new KnowledgeFileJpaEntity();
        kfEntity.setId("file-1");
        kfEntity.setAvatarId(AVATAR_ID);
        kfEntity.setStorageKey("avatars/avatar-math/uploads/notes.pdf");
        when(knowledgeFileRepo.findByAvatarId(AVATAR_ID)).thenReturn(List.of(kfEntity));

        // User-level stubs
        when(referralRepo.findAll()).thenReturn(List.of());
        when(badgeRepo.findByUserId(USER_ID)).thenReturn(List.of());
        when(consentRecordRepo.findAll()).thenReturn(List.of());
        when(consentRequestRepo.findAll()).thenReturn(List.of());
        when(characterUnlockRepo.findAll()).thenReturn(List.of());
        when(userMochiRepo.findById_UserId(USER_ID)).thenReturn(List.of());
        when(userPowerupRepo.findById_UserId(USER_ID)).thenReturn(List.of());
        when(biometricRegistrationRepo.findByUserId(USER_ID)).thenReturn(List.of());
        when(biometricChallengeRepo.findByUserId(USER_ID)).thenReturn(List.of());
        when(emailVerificationTokenRepo.findByUserId(USER_ID)).thenReturn(List.of());
        when(subscriptionRepo.findById(USER_ID)).thenReturn(Optional.empty());

        useCase.execute(USER_ID, JTI, EXPIRY);

        // Knowledge file's storage key was deleted
        verify(storageService).delete("avatars/avatar-math/uploads/notes.pdf");
        // Knowledge file DB row was deleted
        verify(knowledgeFileRepo).deleteById("file-1");
        // Avatar row deleted
        verify(avatarRepo).deleteById(AVATAR_ID);
        // User row deleted
        verify(userRepo).deleteById(USER_ID);
    }

    @Test
    void execute_noJti_tokenRevocationSkipped() {
        stubNoChildren();
        stubEmptyLists();

        // No jti provided (legacy token)
        useCase.execute(USER_ID, null, null);

        verify(userRepo).deleteById(USER_ID);
        verifyNoInteractions(revokedTokenRepo);
    }
}
