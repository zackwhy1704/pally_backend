package com.pally.domain.avatar.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.shared.exception.AvatarNotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Use case: retrieve one or all avatars belonging to a user.
 */
@Service
@RequiredArgsConstructor
public class GetAvatarUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetAvatarUseCase.class);

    private final AvatarRepository avatarRepository;

    public Avatar getById(String avatarId, String userId) {
        log.debug("Fetching avatar id={} userId={}", avatarId, userId);
        return avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));
    }

    public List<Avatar> getAllForUser(String userId) {
        log.debug("Listing avatars for userId={}", userId);
        return avatarRepository.findByUserId(userId);
    }
}
