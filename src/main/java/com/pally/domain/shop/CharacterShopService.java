package com.pally.domain.shop;

import com.pally.domain.progress.StreakService;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.infrastructure.persistence.shop.CharacterUnlockJpaEntity;
import com.pally.infrastructure.persistence.shop.CharacterUnlockJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CharacterShopService {

    private final CharacterUnlockJpaRepository unlockRepo;
    private final UserJpaRepository userRepo;

    private static final List<String> ALL_CHARACTERS = List.of(
            "PENCIL", "SCIENCE", "PE", "ART", "LUNCHBOX", "LIBRARY", "HEADMASTER", "GOLDSTAR");
    private static final List<String> DEFAULT_UNLOCKS = List.of(
            "PENCIL", "SCIENCE", "PE", "ART", "LUNCHBOX", "LIBRARY");

    @Transactional
    public void seedDefaultUnlocks(String userId) {
        for (String c : DEFAULT_UNLOCKS) {
            if (!unlockRepo.existsByUserIdAndCharacter(userId, c)) {
                var entity = new CharacterUnlockJpaEntity();
                entity.setId(IdGenerator.newId());
                entity.setUserId(userId);
                entity.setCharacter(c);
                entity.setUnlockedAt(Instant.now());
                unlockRepo.save(entity);
            }
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getCharacterUnlocks(String userId) {
        var unlocked = unlockRepo.findByUserId(userId).stream()
                .map(CharacterUnlockJpaEntity::getCharacter)
                .collect(Collectors.toSet());

        var characters = ALL_CHARACTERS.stream().map(c -> Map.<String, Object>of(
                "character", c,
                "unlocked", unlocked.contains(c),
                "rarity", getRarity(c)
        )).toList();

        return Map.of("characters", characters);
    }

    public static final int MYSTERY_BOX_COST = 600;
    public static final int MYSTERY_BOX_DUPLICATE_REFUND_COST = 300;

    /**
     * Open a mystery box. The star spend is an atomic conditional UPDATE
     * — two concurrent taps at a 600-star balance can't both succeed.
     * For duplicates we only spend the 300-refund cost; the character
     * unlock is guarded by the {@code unique(user_id, character)} index
     * so the "alreadyOwned" race is impossible to corrupt either.
     */
    @Transactional
    public Map<String, Object> openMysteryBox(String userId) {
        boolean isSecret = ThreadLocalRandom.current().nextInt(24) == 0;
        String awarded = isSecret ? "GOLDSTAR" : "HEADMASTER";
        String rarity = isSecret ? "SECRET" : "RARE";

        boolean alreadyOwned = unlockRepo.existsByUserIdAndCharacter(userId, awarded);
        int cost = alreadyOwned
                ? MYSTERY_BOX_DUPLICATE_REFUND_COST
                : MYSTERY_BOX_COST;

        int updated = userRepo.spendStars(userId, cost);
        if (updated == 0) {
            throw new BusinessException(
                    "Not enough stars (need " + cost + ")", 400);
        }

        if (!alreadyOwned) {
            var unlock = new CharacterUnlockJpaEntity();
            unlock.setId(IdGenerator.newId());
            unlock.setUserId(userId);
            unlock.setCharacter(awarded);
            unlock.setUnlockedAt(Instant.now());
            try {
                unlockRepo.save(unlock);
            } catch (org.springframework.dao.DataIntegrityViolationException dup) {
                // Race: another tap inserted the unlock between our
                // existsBy check and this save. Treat as already-owned —
                // the user already has the character; we already spent
                // the full cost so this is a small inflation, but the
                // alternative (refund attempt) opens up a worse race.
                log.info("[Shop] User {} unlock race for {} — already owned",
                        userId, awarded);
            }
        }

        UserJpaEntity fresh = userRepo.findById(userId).orElseThrow(
                () -> new BusinessException("User not found", 404));
        log.info("[Shop] User {} mystery box → {} ({}) cost={} isNew={}",
                userId, awarded, rarity, cost, !alreadyOwned);
        return Map.of("character", awarded, "rarity", rarity,
                "newStarBalance", fresh.getStars(), "isNew", !alreadyOwned);
    }

    @Transactional
    public Map<String, Object> creditStars(String userId, int amount) {
        var user = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        user.setStars(user.getStars() + amount);
        userRepo.save(user);
        log.info("[Shop] Credited {} stars to user={}, new balance={}", amount, userId, user.getStars());
        return Map.of("stars", user.getStars());
    }

    public static final int FREEZE_COST = 150;

    /**
     * Atomically spend 150 stars for one streak freeze.
     *
     * <p>The cost check + freeze increment happen in a single UPDATE so two
     * concurrent purchases at a 150-balance can never both succeed. Also
     * honours the per-level cap from {@link StreakService#effectiveFreezeCap(int)}
     * (3 by default, 5 at L20). On insufficient stars OR at cap, returns
     * 400 with a kid-friendly message — the caller's UI can choose between
     * "buy more stars" vs "freezes full" based on the {@code reason} field.
     */
    @Transactional
    public Map<String, Object> buyStreakFreeze(String userId) {
        UserJpaEntity user = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        int cap = StreakService.effectiveFreezeCap(user.getLevel());
        if (user.getStreakFreezes() >= cap) {
            // Pre-flight check — gives a precise reason without burning a
            // failed UPDATE. The atomic UPDATE below is still the
            // authoritative guard against the race.
            throw new BusinessException(
                    "Freezes are full (" + cap + ")", 400);
        }
        int updated = userRepo.buyStreakFreeze(userId, FREEZE_COST, cap);
        if (updated == 0) {
            // Race lost OR stars dropped below 150 between SELECT + UPDATE.
            // Read the row to see which.
            UserJpaEntity fresh = userRepo.findById(userId).orElse(user);
            if (fresh.getStars() < FREEZE_COST) {
                throw new BusinessException(
                        "Not enough stars (need " + FREEZE_COST + ")", 400);
            }
            throw new BusinessException(
                    "Freezes are full (" + cap + ")", 400);
        }
        UserJpaEntity fresh = userRepo.findById(userId).orElse(user);
        log.info("[Shop] user={} bought freeze → freezes={}/{} stars={}",
                userId, fresh.getStreakFreezes(), cap, fresh.getStars());
        return Map.of(
                "freezes", fresh.getStreakFreezes(),
                "freezeCap", cap,
                "newStarBalance", fresh.getStars());
    }

    private String getRarity(String character) {
        return switch (character) {
            case "GOLDSTAR" -> "SECRET";
            case "HEADMASTER" -> "RARE";
            default -> "STANDARD";
        };
    }
}
