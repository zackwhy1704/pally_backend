package com.pally.domain.shop;

import com.pally.domain.progress.StreakService;
import com.pally.infrastructure.persistence.mochi.MochiCharacterJpaEntity;
import com.pally.infrastructure.persistence.mochi.MochiCharacterJpaRepository;
import com.pally.infrastructure.persistence.mochi.UserMochiJpaEntity;
import com.pally.infrastructure.persistence.mochi.UserMochiJpaRepository;
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
import java.util.ArrayList;
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
    private final MochiCharacterJpaRepository catalogRepo;
    private final UserMochiJpaRepository userMochiRepo;

    @Transactional
    public void seedDefaultUnlocks(String userId) {
        // Read DEFAULT-acquisition characters from the catalog. Old behaviour:
        // the same 6 (Pencil/Science/PE/Art/Lunchbox/Library). New behaviour:
        // if a future theme adds DEFAULT characters, new users get those too
        // — no code change. We still write to character_unlocks for backward
        // compat AND mirror to user_mochi for the collection screen.
        Instant now = Instant.now();
        for (MochiCharacterJpaEntity ch : activeOf("DEFAULT", now)) {
            if (!unlockRepo.existsByUserIdAndCharacter(userId, ch.getId())) {
                var entity = new CharacterUnlockJpaEntity();
                entity.setId(IdGenerator.newId());
                entity.setUserId(userId);
                entity.setCharacter(ch.getId());
                entity.setUnlockedAt(now);
                unlockRepo.save(entity);
            }
            mirrorToUserMochi(userId, ch.getId(), "DEFAULT");
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getCharacterUnlocks(String userId) {
        var unlocked = unlockRepo.findByUserId(userId).stream()
                .map(CharacterUnlockJpaEntity::getCharacter)
                .collect(Collectors.toSet());

        // Catalog drives the list — adding a new Mochi via INSERT lights it
        // up in every client without a deploy. Filter to active so a sunset
        // seasonal Mochi disappears from the picker.
        Instant now = Instant.now();
        var characters = catalogRepo.findAll().stream()
                .filter(c -> c.isActiveAt(now))
                .map(c -> Map.<String, Object>of(
                        "character", c.getId(),
                        "unlocked", unlocked.contains(c.getId()),
                        "rarity", c.getRarity()))
                .toList();

        return Map.of("characters", characters);
    }

    private List<MochiCharacterJpaEntity> activeOf(String acquisition, Instant when) {
        List<MochiCharacterJpaEntity> active = new ArrayList<>();
        for (MochiCharacterJpaEntity c : catalogRepo.findByAcquisition(acquisition)) {
            if (c.isActiveAt(when)) active.add(c);
        }
        return active;
    }

    private void mirrorToUserMochi(String userId, String mochiId, String via) {
        var id = new UserMochiJpaEntity.Id(userId, mochiId);
        if (userMochiRepo.existsById(id)) return;
        try {
            userMochiRepo.save(new UserMochiJpaEntity(userId, mochiId, via));
        } catch (Exception e) {
            // Race against another grant — the PK will reject the dupe,
            // which is fine. Don't fail the surrounding action over it.
            log.debug("[Shop] mirror to user_mochi race for {}/{}: {}",
                    userId, mochiId, e.getMessage());
        }
    }

    public static final int MYSTERY_BOX_COST = 600;
    public static final int MYSTERY_BOX_DUPLICATE_REFUND_COST = 300;

    /// Per-character draw weights in percent points. The catalog assigns
    /// each Mochi to a rarity; each rarity has a per-character weight.
    /// With 6 COMMONs + 1 RARE + 1 SECRET: 6×15 + 8 + 2 = 100. If the
    /// catalog changes (e.g. a 7th common is added), weights stay per
    /// character — total becomes 7×15+8+2=115 and the ratios shift
    /// proportionally. That's intentional: the SEC/RARE rates are
    /// stable relative to common while content grows.
    private static final int WEIGHT_COMMON = 15;
    private static final int WEIGHT_RARE = 8;
    private static final int WEIGHT_SECRET = 2;

    /**
     * Open a mystery box. The star spend is an atomic conditional UPDATE
     * — two concurrent taps at a 600-star balance can't both succeed.
     * For duplicates we only spend the 300-refund cost; the character
     * unlock is guarded by the {@code unique(user_id, character)} index
     * so the "alreadyOwned" race is impossible to corrupt either.
     *
     * <p>The pool is read from {@code mochi_characters} where
     * {@code acquisition = MYSTERY_BOX} and the window is active — a
     * future seasonal Mochi listed as MYSTERY_BOX will join the pool
     * automatically when its window opens, no deploy.
     */
    @Transactional
    public Map<String, Object> openMysteryBox(String userId) {
        Instant now = Instant.now();
        List<MochiCharacterJpaEntity> pool = activeOf("MYSTERY_BOX", now);
        if (pool.isEmpty()) {
            throw new BusinessException(
                    "Mystery box is closed right now — try again later", 503);
        }

        MochiCharacterJpaEntity awarded = pickWeighted(pool);
        String awardedId = awarded.getId();
        String rarity = awarded.getRarity();

        boolean alreadyOwned = unlockRepo.existsByUserIdAndCharacter(userId, awardedId);
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
            unlock.setCharacter(awardedId);
            unlock.setUnlockedAt(now);
            try {
                unlockRepo.save(unlock);
                mirrorToUserMochi(userId, awardedId, "MYSTERY_BOX");
            } catch (org.springframework.dao.DataIntegrityViolationException dup) {
                // Race: another tap inserted the unlock between our
                // existsBy check and this save. Treat as already-owned —
                // the user already has the character; we already spent
                // the full cost so this is a small inflation, but the
                // alternative (refund attempt) opens up a worse race.
                log.info("[Shop] User {} unlock race for {} — already owned",
                        userId, awardedId);
            }
        }

        UserJpaEntity fresh = userRepo.findById(userId).orElseThrow(
                () -> new BusinessException("User not found", 404));
        log.info("[Shop] User {} mystery box → {} ({}) cost={} isNew={}",
                userId, awardedId, rarity, cost, !alreadyOwned);
        return Map.of("character", awardedId, "rarity", rarity,
                "newStarBalance", fresh.getStars(), "isNew", !alreadyOwned);
    }

    /// True weighted pull. Each character carries the per-rarity weight
    /// (COMMON=15, RARE=8, SECRET=2). With the current Core theme that
    /// gives the design-spec odds: each of 6 commons 15%, headmaster 8%,
    /// goldstar 2%. The roll is a single uniform draw in [0, totalWeight)
    /// mapped to whichever character's window it lands in.
    private MochiCharacterJpaEntity pickWeighted(
            List<MochiCharacterJpaEntity> pool) {
        int total = 0;
        for (var c : pool) total += weightOf(c);
        if (total <= 0) {
            // Catalog corruption (all UNKNOWN rarity) — better award
            // SOMETHING than 500. Fair fallback: uniform.
            return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        }
        int roll = ThreadLocalRandom.current().nextInt(total);
        int accum = 0;
        for (var c : pool) {
            accum += weightOf(c);
            if (roll < accum) return c;
        }
        // Floating point can't bite here (we're in ints), but be safe.
        return pool.get(pool.size() - 1);
    }

    static int weightOf(MochiCharacterJpaEntity c) {
        return switch (c.getRarity()) {
            case "COMMON" -> WEIGHT_COMMON;
            case "RARE"   -> WEIGHT_RARE;
            case "SECRET" -> WEIGHT_SECRET;
            default       -> 0;
        };
    }

    /// Public catalog hook for the "FYI — Probability" UI. Returns each
    /// active MYSTERY_BOX character with its odds as a percentage of the
    /// current total weight — kid-friendly numbers the Flutter screen
    /// just renders verbatim instead of hardcoding.
    @Transactional(readOnly = true)
    public List<Map<String, Object>> mysteryBoxOdds() {
        Instant now = Instant.now();
        List<MochiCharacterJpaEntity> pool = activeOf("MYSTERY_BOX", now);
        int total = 0;
        for (var c : pool) total += weightOf(c);
        if (total == 0) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (var c : pool) {
            int w = weightOf(c);
            if (w == 0) continue;
            result.add(Map.of(
                    "character", c.getId(),
                    "name", c.getName(),
                    "rarity", c.getRarity(),
                    "weight", w,
                    "percent", Math.round(100.0 * w / total)));
        }
        return result;
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

}
