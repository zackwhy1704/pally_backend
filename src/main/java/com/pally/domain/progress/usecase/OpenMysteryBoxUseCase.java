package com.pally.domain.progress.usecase;

import com.pally.domain.progress.ProgressSummary;
import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
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

        User stats = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));

        if (stats.getStars() < BOX_COST) {
            throw new BusinessException(
                    "Not enough stars. Need " + BOX_COST + ", have " + stats.getStars(),
                    422
            );
        }

        // Deduct stars, also award a small XP bonus per opening so the box
        // contributes to leveling, not just collection.
        stats.setStars(stats.getStars() - BOX_COST);
        int newXp = stats.getXp() + BOX_XP_BONUS;
        stats.setXp(newXp);
        stats.setLevel(ProgressSummary.computeLevel(newXp));
        userRepository.save(stats);

        // Return a random character type as the reward
        com.pally.domain.avatar.CharacterType[] characters = com.pally.domain.avatar.CharacterType.values();
        return characters[(int) (Math.random() * characters.length)].name();
    }
}
