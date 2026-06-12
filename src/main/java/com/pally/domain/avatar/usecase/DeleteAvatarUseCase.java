package com.pally.domain.avatar.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.exception.BusinessException;
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
        Avatar avatar = avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));
        // Students may not delete a class avatar — it is centre-owned and its
        // content history is referenced by class analytics. The centre manages
        // its lifecycle (archive/remove), never the student client.
        if (avatar.isCentreClass()) {
            throw new BusinessException("Class avatars cannot be deleted", 403);
        }
        avatarRepository.deleteById(avatarId);
        log.info("Avatar deleted id={}", avatarId);
    }
}
