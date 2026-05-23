package com.pally.domain.avatar.usecase;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.shared.exception.AvatarNotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Use case: delete an avatar and all its associated data via cascade.
 */
@Service
@RequiredArgsConstructor
public class DeleteAvatarUseCase {

    private static final Logger log = LoggerFactory.getLogger(DeleteAvatarUseCase.class);

    private final AvatarRepository avatarRepository;

    public void execute(String avatarId, String userId) {
        log.info("Deleting avatar id={} userId={}", avatarId, userId);
        if (!avatarRepository.existsByIdAndUserId(avatarId, userId)) {
            throw new AvatarNotFoundException(avatarId);
        }
        avatarRepository.deleteById(avatarId);
        log.info("Avatar deleted id={}", avatarId);
    }
}
