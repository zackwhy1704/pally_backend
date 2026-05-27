package com.pally.domain.shop;

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

    @Transactional
    public Map<String, Object> openMysteryBox(String userId) {
        var user = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));

        if (user.getStars() < 600) {
            throw new BusinessException("Not enough stars (need 600)", 400);
        }

        boolean isSecret = ThreadLocalRandom.current().nextInt(24) == 0;
        String awarded = isSecret ? "GOLDSTAR" : "HEADMASTER";
        String rarity = isSecret ? "SECRET" : "RARE";

        boolean alreadyOwned = unlockRepo.existsByUserIdAndCharacter(userId, awarded);

        if (alreadyOwned) {
            user.setStars(user.getStars() - 300);
            userRepo.save(user);
            return Map.of("character", awarded, "rarity", rarity,
                    "newStarBalance", user.getStars(), "isNew", false);
        }

        user.setStars(user.getStars() - 600);
        userRepo.save(user);

        var unlock = new CharacterUnlockJpaEntity();
        unlock.setId(IdGenerator.newId());
        unlock.setUserId(userId);
        unlock.setCharacter(awarded);
        unlock.setUnlockedAt(Instant.now());
        unlockRepo.save(unlock);

        log.info("[Shop] User {} unlocked {} ({})", userId, awarded, rarity);
        return Map.of("character", awarded, "rarity", rarity,
                "newStarBalance", user.getStars(), "isNew", true);
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

    private String getRarity(String character) {
        return switch (character) {
            case "GOLDSTAR" -> "SECRET";
            case "HEADMASTER" -> "RARE";
            default -> "STANDARD";
        };
    }
}
