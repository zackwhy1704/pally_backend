package com.pally.domain.avatar.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.chat.port.ChatSessionCachePort;
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
    /// Reached through the domain PORT (impl: infrastructure CacheKeepAliveService) so
    /// the keepalive ticker is cancelled the instant the avatar is deleted. Without this,
    /// the scheduled ping kept polling the dead avatar every ~4 min (self-healing only on
    /// the NEXT ping's null-avatar read, up to 4 min later + a wasted findById each cycle).
    private final ChatSessionCachePort chatSessionCachePort;

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
        // Cancel the cache keepalive ticker BEFORE the row disappears so no ping
        // ever fires for a deleted avatar (the keepalive leak). Best-effort — a
        // cancel failure must never block the delete itself.
        try {
            chatSessionCachePort.stopKeepalive(avatarId);
        } catch (Exception e) {
            log.warn("[Delete] stopKeepalive failed for avatar={} (non-fatal): {}",
                    avatarId, e.getMessage());
        }
        avatarRepository.deleteById(avatarId);
        log.info("Avatar deleted id={}", avatarId);
    }
}
