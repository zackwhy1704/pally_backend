package com.pally.domain.progress.usecase;

import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OpenMysteryBoxUseCase {

    static final int BOX_COST = 600;
    static final int BOX_XP_BONUS = 10;

    private final UserRepository userRepository;

    @Transactional
    public String execute(String userId) {
        userRepository.ensureUserExists(userId);

        int updated = userRepository.spendStars(userId, BOX_COST);
        if (updated == 0) {
            int balance = userRepository.findById(userId).map(User::getStars).orElse(0);
            throw new BusinessException(
                    "Not enough stars. Need " + BOX_COST + ", have " + balance, 422);
        }

        userRepository.addXpAndStars(userId, BOX_XP_BONUS, 0);

        com.pally.domain.avatar.CharacterType[] characters =
                com.pally.domain.avatar.CharacterType.values();
        return characters[(int) (Math.random() * characters.length)].name();
    }
}
