package com.pally.domain.avatar.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.SeedContentService;
import com.pally.domain.progress.LevelRewards;
import com.pally.domain.progress.UserRepository;
import com.pally.domain.subscription.PremiumService;
import com.pally.shared.exception.UpgradeRequiredException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Use case: create a new avatar for a user.
 *
 * <p>Also seeds a starter wiki pack so the brand-new tutor can answer
 * questions on the chosen subject before the child uploads anything —
 * fixes the cold-start activation hole called out in the audit.
 */
@Service
@RequiredArgsConstructor
public class CreateAvatarUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreateAvatarUseCase.class);

    private final AvatarRepository avatarRepository;
    private final SeedContentService seedContentService;
    private final PremiumService premiumService;
    private final UserRepository userRepository;

    public Avatar execute(String userId, String name, Subject subject, CharacterType characterType) {
        return execute(userId, name, subject, characterType, null, null);
    }

    public Avatar execute(String userId, String name, Subject subject, CharacterType characterType,
                          String gradeLevel, String curriculumType) {
        log.info("Creating avatar for userId={} name={} subject={} grade={}", userId, name, subject, gradeLevel);

        // Free-tier cap. Read existing count BEFORE the save so we don't
        // off-by-one on the call that's trying to create the second tutor.
        // L5+ gets an extra slot per LevelRewards.freeTutorCap — turns the
        // freemium wall into a goal, not a hard block.
        if (!premiumService.resolve(userId).isPremium()) {
            int existing = avatarRepository.findByUserId(userId).size();
            int level = userRepository.findById(userId)
                    .map(u -> u.level())
                    .orElse(1);
            int cap = LevelRewards.freeTutorCap(level);
            if (existing >= cap) {
                throw new UpgradeRequiredException("CREATE_TUTOR");
            }
        }

        Avatar avatar = Avatar.create(userId, name, subject, characterType, gradeLevel, curriculumType);
        Avatar saved = avatarRepository.save(avatar);

        // Cold-start seed pack — best effort. A missing pack or DB hiccup
        // must never block avatar creation; the user can always upload
        // their own notes and the tutor still works.
        try {
            int seeded = seedContentService.seedForAvatar(
                    saved.getId(), subject.name());
            if (seeded > 0) {
                saved.setWikiPageCount(seeded);
                avatarRepository.save(saved);
            }
        } catch (Exception e) {
            log.warn("[Seed] avatar={} subject={} failed (non-fatal): {}",
                    saved.getId(), subject, e.getMessage());
        }

        log.info("Avatar created id={}", saved.getId());
        return saved;
    }
}
