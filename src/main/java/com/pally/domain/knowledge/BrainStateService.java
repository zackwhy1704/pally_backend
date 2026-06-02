package com.pally.domain.knowledge;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thin transactional service that updates the avatar's brainState column.
 * Each method is a short standalone transaction — never wrap a Claude compile
 * call inside the same transaction as a brainState write.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BrainStateService {

    private final AvatarRepository avatarRepository;

    @Transactional
    public void markPending(String avatarId) {
        updateState(avatarId, Avatar.BrainState.PENDING_RECOMPILE);
    }

    @Transactional
    public void markCompiling(String avatarId) {
        updateState(avatarId, Avatar.BrainState.COMPILING);
    }

    @Transactional
    public void markReady(String avatarId) {
        updateState(avatarId, Avatar.BrainState.READY);
    }

    private void updateState(String avatarId, Avatar.BrainState state) {
        avatarRepository.findById(avatarId).ifPresentOrElse(a -> {
            a.setBrainState(state);
            avatarRepository.save(a);
            log.debug("[Brain] {} → {}", avatarId, state);
        }, () -> log.warn("[Brain] Avatar {} not found for state update to {}", avatarId, state));
    }
}
