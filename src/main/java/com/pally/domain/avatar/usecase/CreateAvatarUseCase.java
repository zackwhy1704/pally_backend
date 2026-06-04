package com.pally.domain.avatar.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.consent.ConsentGuard;
import com.pally.domain.progress.LevelRewards;
import com.pally.domain.progress.UserRepository;
import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.SubscriptionLimits;
import com.pally.domain.subscription.SubscriptionTier;
import com.pally.shared.exception.UpgradeRequiredException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Use case: create a new avatar for a user.
 *
 * <p>Seeding removed: wiki starter packs were indistinguishable from the
 * student's own notes, which (1) confused the library and (2) undermined
 * the "trained on YOUR notes" moat. Option-B base-model fallback already
 * handles the cold-start case — a fresh Mochi with 0 pages answers
 * in-subject questions from general knowledge, clearly labelled.
 * it can be deleted in a future cleanup migration.
 */
@Service
@RequiredArgsConstructor
public class CreateAvatarUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreateAvatarUseCase.class);

    private final AvatarRepository avatarRepository;
    private final PremiumService premiumService;
    private final UserRepository userRepository;
    private final ConsentGuard consentGuard;

    public Avatar execute(String userId, String name, Subject subject, CharacterType characterType) {
        return execute(userId, name, subject, characterType, null, null);
    }

    public Avatar execute(String userId, String name, Subject subject, CharacterType characterType,
                          String gradeLevel, String curriculumType) {
        log.info("Creating avatar for userId={} name={} subject={} grade={}", userId, name, subject, gradeLevel);

        // PDPA gate: creating a personal tutor (which persists data) requires ACTIVE.
        consentGuard.requireActive(userId, "CREATE_TUTOR");

        // Tier-aware Mochi cap. Read existing count BEFORE the save so we don't
        // off-by-one on the call that's trying to create the next tutor.
        // FREE users: L5+ gets an extra slot per LevelRewards.freeTutorCap.
        // PRO users: 5 tutors.
        // MAX / FAMILY / CENTRE: unlimited.
        SubscriptionTier tier = premiumService.resolveTier(userId);
        if (tier != SubscriptionTier.MAX
                && tier != SubscriptionTier.FAMILY
                && tier != SubscriptionTier.CENTRE) {
            int existing = avatarRepository.findByUserId(userId).size();
            int level = userRepository.findById(userId)
                    .map(u -> u.level())
                    .orElse(1);
            int cap = SubscriptionLimits.mochiCap(tier, level);
            if (existing >= cap) {
                throw new UpgradeRequiredException("CREATE_TUTOR");
            }
        }

        Avatar avatar = Avatar.create(userId, name, subject, characterType, gradeLevel, curriculumType);
        Avatar saved = avatarRepository.save(avatar);

        // New avatar starts with wikiPageCount = 0.
        // The student uploads their own notes; Option-B answers general
        // in-subject questions (with disclaimer) before any upload.

        log.info("Avatar created id={}", saved.getId());
        return saved;
    }
}
