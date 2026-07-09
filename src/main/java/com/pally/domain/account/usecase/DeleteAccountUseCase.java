package com.pally.domain.account.usecase;

import com.pally.domain.subscription.PremiumService;
import com.pally.infrastructure.persistence.activity.ActivityLogJpaRepository;
import com.pally.infrastructure.persistence.auth.BiometricChallengeJpaRepository;
import com.pally.infrastructure.persistence.auth.BiometricRegistrationJpaRepository;
import com.pally.infrastructure.persistence.auth.EmailVerificationTokenJpaRepository;
import com.pally.infrastructure.persistence.auth.RevokedTokenJpaEntity;
import com.pally.infrastructure.persistence.auth.RevokedTokenJpaRepository;
import com.pally.infrastructure.persistence.avatar.AvatarJpaRepository;
import com.pally.infrastructure.persistence.badge.UserBadgeJpaRepository;
import com.pally.infrastructure.persistence.chat.ChatMessageJpaRepository;
import com.pally.infrastructure.persistence.chat.ChatSessionJpaRepository;
import com.pally.infrastructure.persistence.chat.ChatSessionSummaryJpaRepository;
import com.pally.infrastructure.persistence.chat.HintTreeJpaRepository;
import com.pally.infrastructure.persistence.consent.ConsentRequestJpaRepository;
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
import com.pally.infrastructure.persistence.cost.AiUsageJpaRepository;
import com.pally.infrastructure.persistence.assignment.ContentGapSignalJpaRepository;
import com.pally.infrastructure.persistence.homework.HomeworkSubmissionJpaRepository;
import com.pally.infrastructure.persistence.quiz.QuizAnswerKeyJpaRepository;
import com.pally.infrastructure.persistence.quiz.QuizSubmissionIdempotencyJpaRepository;
import com.pally.infrastructure.persistence.auth.AuthChallengeJpaRepository;
import com.pally.infrastructure.persistence.weakness.WeaknessProfileStateJpaRepository;
import com.pally.infrastructure.storage.StorageService;
import com.pally.infrastructure.stripe.StripeService;
import com.pally.shared.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Permanently deletes a user account and ALL associated data.
 *
 * <p>Deletion is blocked if the user is a PARENT with linked children —
 * those children's data belongs to them, not to the parent.
 *
 * <p>Cascade order respects FK constraints (children before parents):
 * {@code chat_messages / sessions → flashcards / quiz_results → wiki_pages → knowledge_files
 * → avatars → badges / activity / consent → subscription → users}.
 *
 * <p>Stripe subscription is cancelled best-effort; a Stripe failure does not
 * abort the deletion.
 */
@Service
@Slf4j
public class DeleteAccountUseCase {

    // ── Repositories ──────────────────────────────────────────────────────

    private final UserRepository userRepo;
    private final AvatarJpaRepository avatarRepo;
    private final ChatMessageJpaRepository chatMessageRepo;
    private final ChatSessionJpaRepository chatSessionRepo;
    private final ChatSessionSummaryJpaRepository chatSessionSummaryRepo;
    private final HintTreeJpaRepository hintTreeRepo;
    private final ChatSafetyFlagJpaRepository chatSafetyFlagRepo;
    private final FlashcardJpaRepository flashcardRepo;
    private final QuizAnswerRecordJpaRepository quizAnswerRepo;
    private final QuizQuestionResultJpaRepository quizQuestionResultRepo;
    private final WikiPageSourceJpaRepository wikiPageSourceRepo;
    private final WikiPageJpaRepository wikiPageRepo;
    private final KnowledgeFileJpaRepository knowledgeFileRepo;
    private final ActivityLogJpaRepository activityLogRepo;
    private final StudyPlanCompletionJpaRepository studyPlanCompletionRepo;
    private final UserBadgeJpaRepository badgeRepo;
    private final ConsentRequestJpaRepository consentRequestRepo;
    private final ReferralJpaRepository referralRepo;
    private final CharacterUnlockJpaRepository characterUnlockRepo;
    private final UserMochiJpaRepository userMochiRepo;
    private final UserPowerupJpaRepository userPowerupRepo;
    private final BiometricRegistrationJpaRepository biometricRegistrationRepo;
    private final BiometricChallengeJpaRepository biometricChallengeRepo;
    private final EmailVerificationTokenJpaRepository emailVerificationTokenRepo;
    private final SubscriptionJpaRepository subscriptionRepo;
    private final com.pally.infrastructure.persistence.star.StarAwardLogJpaRepository starAwardLogRepo;
    private final com.pally.infrastructure.persistence.assignment.AssignmentJpaRepository assignmentRepo;
    private final StorageService storageService;
    private final StripeService stripeService;
    private final PremiumService premiumService;
    private final RevokedTokenJpaRepository revokedTokenRepo;
    // ── Account-deletion survivors (anonymize) + no-FK orphans (delete) ────────
    private final AiUsageJpaRepository aiUsageRepo;
    private final ContentGapSignalJpaRepository contentGapSignalRepo;
    private final HomeworkSubmissionJpaRepository homeworkSubmissionRepo;
    private final QuizAnswerKeyJpaRepository quizAnswerKeyRepo;
    private final QuizSubmissionIdempotencyJpaRepository quizSubmissionIdempotencyRepo;
    private final AuthChallengeJpaRepository authChallengeRepo;
    private final WeaknessProfileStateJpaRepository weaknessProfileStateRepo;

    // ── Constructor injection (no @Autowired fields) ───────────────────────

    public DeleteAccountUseCase(
            UserRepository userRepo,
            AvatarJpaRepository avatarRepo,
            ChatMessageJpaRepository chatMessageRepo,
            ChatSessionJpaRepository chatSessionRepo,
            ChatSessionSummaryJpaRepository chatSessionSummaryRepo,
            HintTreeJpaRepository hintTreeRepo,
            ChatSafetyFlagJpaRepository chatSafetyFlagRepo,
            FlashcardJpaRepository flashcardRepo,
            QuizAnswerRecordJpaRepository quizAnswerRepo,
            QuizQuestionResultJpaRepository quizQuestionResultRepo,
            WikiPageSourceJpaRepository wikiPageSourceRepo,
            WikiPageJpaRepository wikiPageRepo,
            KnowledgeFileJpaRepository knowledgeFileRepo,
            ActivityLogJpaRepository activityLogRepo,
            StudyPlanCompletionJpaRepository studyPlanCompletionRepo,
            UserBadgeJpaRepository badgeRepo,
            ConsentRequestJpaRepository consentRequestRepo,
            ReferralJpaRepository referralRepo,
            CharacterUnlockJpaRepository characterUnlockRepo,
            UserMochiJpaRepository userMochiRepo,
            UserPowerupJpaRepository userPowerupRepo,
            BiometricRegistrationJpaRepository biometricRegistrationRepo,
            BiometricChallengeJpaRepository biometricChallengeRepo,
            EmailVerificationTokenJpaRepository emailVerificationTokenRepo,
            SubscriptionJpaRepository subscriptionRepo,
            com.pally.infrastructure.persistence.star.StarAwardLogJpaRepository starAwardLogRepo,
            com.pally.infrastructure.persistence.assignment.AssignmentJpaRepository assignmentRepo,
            StorageService storageService,
            StripeService stripeService,
            PremiumService premiumService,
            RevokedTokenJpaRepository revokedTokenRepo,
            AiUsageJpaRepository aiUsageRepo,
            ContentGapSignalJpaRepository contentGapSignalRepo,
            HomeworkSubmissionJpaRepository homeworkSubmissionRepo,
            QuizAnswerKeyJpaRepository quizAnswerKeyRepo,
            QuizSubmissionIdempotencyJpaRepository quizSubmissionIdempotencyRepo,
            AuthChallengeJpaRepository authChallengeRepo,
            WeaknessProfileStateJpaRepository weaknessProfileStateRepo) {

        this.userRepo                  = userRepo;
        this.avatarRepo                = avatarRepo;
        this.chatMessageRepo           = chatMessageRepo;
        this.chatSessionRepo           = chatSessionRepo;
        this.chatSessionSummaryRepo    = chatSessionSummaryRepo;
        this.hintTreeRepo              = hintTreeRepo;
        this.chatSafetyFlagRepo        = chatSafetyFlagRepo;
        this.flashcardRepo             = flashcardRepo;
        this.quizAnswerRepo            = quizAnswerRepo;
        this.quizQuestionResultRepo    = quizQuestionResultRepo;
        this.wikiPageSourceRepo        = wikiPageSourceRepo;
        this.wikiPageRepo              = wikiPageRepo;
        this.knowledgeFileRepo         = knowledgeFileRepo;
        this.activityLogRepo           = activityLogRepo;
        this.studyPlanCompletionRepo   = studyPlanCompletionRepo;
        this.badgeRepo                 = badgeRepo;
        this.consentRequestRepo        = consentRequestRepo;
        this.referralRepo              = referralRepo;
        this.characterUnlockRepo       = characterUnlockRepo;
        this.userMochiRepo             = userMochiRepo;
        this.userPowerupRepo           = userPowerupRepo;
        this.biometricRegistrationRepo = biometricRegistrationRepo;
        this.biometricChallengeRepo    = biometricChallengeRepo;
        this.emailVerificationTokenRepo = emailVerificationTokenRepo;
        this.subscriptionRepo          = subscriptionRepo;
        this.starAwardLogRepo          = starAwardLogRepo;
        this.assignmentRepo            = assignmentRepo;
        this.storageService            = storageService;
        this.stripeService             = stripeService;
        this.premiumService            = premiumService;
        this.revokedTokenRepo          = revokedTokenRepo;
        this.aiUsageRepo               = aiUsageRepo;
        this.contentGapSignalRepo      = contentGapSignalRepo;
        this.homeworkSubmissionRepo    = homeworkSubmissionRepo;
        this.quizAnswerKeyRepo         = quizAnswerKeyRepo;
        this.quizSubmissionIdempotencyRepo = quizSubmissionIdempotencyRepo;
        this.authChallengeRepo         = authChallengeRepo;
        this.weaknessProfileStateRepo  = weaknessProfileStateRepo;
    }

    /**
     * Permanently deletes the user and all their data.
     *
     * @param userId        the authenticated user to delete
     * @param jti           the JWT ID of the current token (for revocation); null for legacy tokens
     * @param tokenExpiresAt expiration of the current token; used to set expires_at on revoked_tokens
     * @throws BusinessException 409 if the user is a PARENT with linked children
     */
    @Transactional
    public void execute(String userId, String jti, java.time.Instant tokenExpiresAt) {
        // ── Step 1: block if this user is a PARENT with linked children ───────
        // Children's data is theirs — we must not cascade-delete it.
        int childCount = userRepo.countByParentId(userId);
        if (childCount > 0) {
            throw new BusinessException(
                    "Please unlink all child accounts before deleting your account.", 409);
        }

        // ── Step 2: cancel Stripe subscription (best-effort) ─────────────────
        subscriptionRepo.findById(userId).ifPresent(sub -> {
            try {
                stripeService.cancelSubscriptionForUser(sub.getStripeSubscriptionId());
            } catch (Exception e) {
                // Log and continue — account deletion must not be blocked by Stripe.
                log.warn("[AccountDeletion] Stripe cancellation failed for userId={}: {}",
                        userId, e.getMessage());
            }
        });

        // ── Step 3: cascade-delete per-avatar data ────────────────────────────
        List<String> avatarIds = avatarRepo.findByUserId(userId)
                .stream()
                .map(a -> a.getId())
                .toList();

        for (String avatarId : avatarIds) {
            deleteAvatarData(avatarId);
        }

        // ── Step 4: delete user-level data ────────────────────────────────────
        // Referrals (both as referrer and referee)
        referralRepo.findAll().stream()
                .filter(r -> userId.equals(r.getReferrerUserId()) || userId.equals(r.getRefereeUserId()))
                .forEach(r -> referralRepo.deleteById(r.getId()));

        // Badges / achievements
        badgeRepo.findByUserId(userId).forEach(b -> badgeRepo.deleteById(b.getId()));

        // Activity logs and study plan completions
        activityLogRepo.deleteAllByUserId(userId);
        studyPlanCompletionRepo.deleteAllByUserId(userId);

        // ── Consent proof: RETAIN, do NOT delete (LOCKED survivor policy + DPO) ──────
        // consent_records + consent_requests are the PDPA/PDPC evidence that a valid
        // (parental) consent was obtained; their purpose activates AT and AFTER erasure
        // ("on what basis did you process this child's data?"). They SURVIVE the purge —
        // V119 dropped their ON DELETE CASCADE FK to users, and we no longer delete them
        // here. consent_records is already minimal (who/what/when/mechanism), retained
        // verbatim. We only SCRUB the reusable approval token on consent_requests
        // (minimization — keep the proof, not a live secret). Retention DURATION is a
        // DPIA open item. The retained rows are EVIDENCE-ONLY (no operational read path
        // queries consent by a purged user id).
        consentRequestRepo.scrubTokensByChildUserId(userId);

        // Shop / collectibles
        characterUnlockRepo.findAll().stream()
                .filter(c -> userId.equals(c.getUserId()))
                .forEach(characterUnlockRepo::delete);
        userMochiRepo.findById_UserId(userId)
                .forEach(userMochiRepo::delete);
        userPowerupRepo.findById_UserId(userId)
                .forEach(userPowerupRepo::delete);

        // Auth / biometric
        biometricRegistrationRepo.findByUserId(userId)
                .forEach(b -> biometricRegistrationRepo.deleteById(b.getId()));
        biometricChallengeRepo.findByUserId(userId)
                .forEach(b -> biometricChallengeRepo.deleteById(b.getId()));
        emailVerificationTokenRepo.findByUserId(userId)
                .forEach(t -> emailVerificationTokenRepo.deleteById(t.getToken()));

        // Subscription row
        subscriptionRepo.deleteById(userId);

        // ── Step 4c: account-deletion survivors + no-FK orphans (the DPIA map) ────
        // These tables reference the user by a PLAIN column (no FK / no ON DELETE), so
        // deleting the user row below would NOT touch them — they must be handled here,
        // BEFORE the user delete, inside this same transaction so a crash can't leave a
        // purged user with rows still pointing at the dead id.
        //   SURVIVORS → anonymize-in-place (keep the row, null the identity):
        aiUsageRepo.anonymizeByUserId(userId);              // cost ledger survives
        contentGapSignalRepo.anonymizeByUserId(userId);     // teacher gap signal survives
        //   CONTENTFUL / EPHEMERAL / SIGNAL orphans → delete:
        homeworkSubmissionRepo.deleteByStudentId(userId);   // embeds the student's own work
        authChallengeRepo.deleteByUserId(userId);           // short-lived auth codes
        weaknessProfileStateRepo.deleteByUserId(userId);    // per-user weakness state
        quizSubmissionIdempotencyRepo.deleteByUserId(userId); // idempotency guard rows

        // ── Step 4b: clear the RESTRICT FKs that would otherwise ABORT the delete ──
        // star_award_log.(parent_id|child_id) and assignment.student_id are FKs to users
        // with NO ON DELETE, so any such row makes the user-row delete below throw a
        // constraint violation and roll back the ENTIRE deletion (a half-abort). Clear
        // them first. (Latent when the tables are empty — real for any parent/child/centre.)
        starAwardLogRepo.deleteByParticipant(userId);
        assignmentRepo.deleteByStudentId(userId);

        // ── Step 5: delete the user row itself ────────────────────────────────
        userRepo.deleteById(userId);

        // ── Step 5b: revoke the current JWT so the caller can't re-use it ────
        // Tokens without jti (minted before V55) are non-revocable; log warning.
        if (jti != null && tokenExpiresAt != null) {
            try {
                revokedTokenRepo.save(new RevokedTokenJpaEntity(jti, java.time.Instant.now(), tokenExpiresAt));
            } catch (Exception e) {
                log.warn("[AccountDeletion] Token revocation failed for jti={}: {}",
                        jti, e.getMessage());
            }
        } else {
            log.warn("[AccountDeletion] Token has no jti for userId={} — token will expire naturally",
                    userId);
        }

        // ── Step 6: evict from entitlement cache ─────────────────────────────
        try {
            premiumService.evictEntitlement(userId);
        } catch (Exception e) {
            log.warn("[AccountDeletion] Cache eviction failed for userId={} (non-fatal): {}",
                    userId, e.getMessage());
        }

        log.info("[AccountDeletion] Account fully deleted userId={}", userId);
    }

    /** Deletes all data associated with a single avatar (chat, wiki, files, etc.). */
    private void deleteAvatarData(String avatarId) {
        // Chat messages and sessions (children of avatar)
        chatMessageRepo.findAll().stream()
                .filter(m -> avatarId.equals(m.getAvatarId()))
                .forEach(m -> chatMessageRepo.deleteById(m.getId()));
        chatSessionSummaryRepo.findAll().stream()
                .filter(sum -> avatarId.equals(sum.getAvatarId()))
                .forEach(chatSessionSummaryRepo::delete);
        chatSessionRepo.findAll().stream()
                .filter(s -> avatarId.equals(s.getAvatarId()))
                .forEach(s -> chatSessionRepo.deleteById(s.getId()));
        chatSafetyFlagRepo.findAll().stream()
                .filter(f -> avatarId.equals(f.getAvatarId()))
                .forEach(f -> chatSafetyFlagRepo.deleteById(f.getId()));
        hintTreeRepo.findAll().stream()
                .filter(h -> avatarId.equals(h.getAvatarId()))
                .forEach(h -> hintTreeRepo.deleteById(h.getId()));

        // Flashcards and quiz results
        flashcardRepo.findByAvatarId(avatarId)
                .forEach(fc -> flashcardRepo.deleteById(fc.getId()));
        quizAnswerRepo.findAll().stream()
                .filter(q -> avatarId.equals(q.getAvatarId()))
                .forEach(q -> quizAnswerRepo.deleteById(q.getId()));
        quizQuestionResultRepo.findAll().stream()
                .filter(q -> avatarId.equals(q.getAvatarId()))
                .forEach(q -> quizQuestionResultRepo.deleteById(q.getId()));

        // Study plan completions — keyed by userId not avatarId; delete in user phase

        // Wiki pages (provenance sources first via FK-safe deleteByWikiPageId, then pages)
        wikiPageRepo.findByAvatarId(avatarId).forEach(page ->
                wikiPageSourceRepo.deleteByWikiPageId(page.getId()));
        wikiPageRepo.deleteByAvatarId(avatarId);

        // Knowledge files (storage + db row)
        knowledgeFileRepo.findByAvatarId(avatarId).forEach(file -> {
            try {
                storageService.delete(file.getStorageKey());
            } catch (Exception e) {
                log.warn("[AccountDeletion] Storage delete failed key={}: {}",
                        file.getStorageKey(), e.getMessage());
            }
            knowledgeFileRepo.deleteById(file.getId());
        });

        // Account-deletion: avatar-keyed no-FK rows that would NOT cascade with the
        // avatar row (no ON DELETE). ai_usage is a SURVIVOR — anonymize (null avatar_id),
        // keep the cost row; quiz_answer_keys are orphans — delete. See the DPIA map.
        aiUsageRepo.anonymizeByAvatarId(avatarId);
        quizAnswerKeyRepo.deleteByAvatarId(avatarId);

        // Avatar row itself (cascades wiki_pages/flashcards/chat/modules/quiz_sessions
        // /quiz_question_results via ON DELETE CASCADE on avatars(id)).
        avatarRepo.deleteById(avatarId);
    }
}
