package com.pally.domain.progress.usecase;

import com.pally.domain.progress.UserRepository;
import com.pally.domain.progress.UserStats;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OpenMysteryBoxUseCase {

    private static final int BOX_COST = 600;
    private static final int BOX_XP_BONUS = 10;

    private final UserRepository userRepository;

    public String execute(String userId) {
        userRepository.ensureUserExists(userId);

        UserStats stats = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));

        if (stats.stars() < BOX_COST) {
            throw new BusinessException(
                    "Not enough stars. Need " + BOX_COST + ", have " + stats.stars(),
                    422
            );
        }

        // Deduct stars, also award a small XP bonus per opening so the box
        // contributes to leveling, not just collection.
        userRepository.save(
                stats.withStars(stats.stars() - BOX_COST).withXp(stats.xp() + BOX_XP_BONUS));

        // Return a random character type as the reward
        com.pally.domain.avatar.CharacterType[] characters = com.pally.domain.avatar.CharacterType.values();
        return characters[(int) (Math.random() * characters.length)].name();
    }
}
