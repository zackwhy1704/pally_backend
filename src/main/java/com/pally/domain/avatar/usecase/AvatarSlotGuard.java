package com.pally.domain.avatar.usecase;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Shared guard that enforces the is_active (slot) constraint.
 *
 * <p>Call {@link #requireActive(String, String)} at the very start of any
 * use-case or controller method that mutates or uses the avatar's brain:
 * upload, chat, quiz, teach, manual wiki recompile.
 *
 * <p><b>DELETE is exempt</b> — locked users must still be able to clean up
 * files and delete avatars. Never add this guard to
 * {@code DeleteFileUseCase} or {@code DeleteAvatarUseCase}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AvatarSlotGuard {

    private final AvatarRepository avatarRepository;

    /**
     * Throws {@link BusinessException} (HTTP 409) if the avatar is locked
     * ({@code is_active=false}). Throws {@link AvatarNotFoundException} if
     * the avatar does not exist or does not belong to {@code userId}.
     *
     * <p>DELETE paths are exempt — do not call this method from delete use-cases.
     */
    public void requireActive(String avatarId, String userId) {
        avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .ifPresentOrElse(
                        avatar -> {
                            if (!avatar.isActive()) {
                                log.warn("[SlotGuard] Blocked request to locked avatar={} user={}",
                                        avatarId, userId);
                                throw new BusinessException(
                                        "This Mochi is locked — activate a slot to use it.", 409);
                            }
                        },
                        () -> {
                            throw new AvatarNotFoundException(avatarId);
                        });
    }
}
