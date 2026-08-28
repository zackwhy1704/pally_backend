package com.pally.infrastructure.persistence.block;

import com.pally.domain.block.BlockedUserRepository;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.util.IdGenerator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class BlockedUserRepositoryAdapter implements BlockedUserRepository {

    private final BlockedUserJpaRepository jpa;
    private final UserJpaRepository userRepo;

    public BlockedUserRepositoryAdapter(BlockedUserJpaRepository jpa, UserJpaRepository userRepo) {
        this.jpa = jpa;
        this.userRepo = userRepo;
    }

    @Override
    public Set<String> blockedBy(String blockerUserId) {
        if (blockerUserId == null) return Set.of();
        return jpa.findByBlockerUserId(blockerUserId).stream()
                .map(BlockedUserJpaEntity::getBlockedUserId)
                .collect(Collectors.toSet());
    }

    @Override
    @Transactional
    public void block(String blockerUserId, String blockedUserId) {
        // Idempotent. The unique index in V135 is the real guarantee; this check
        // just avoids a pointless constraint violation on a double-tap.
        if (jpa.existsByBlockerUserIdAndBlockedUserId(blockerUserId, blockedUserId)) return;
        BlockedUserJpaEntity e = new BlockedUserJpaEntity();
        e.setId(IdGenerator.newId());
        e.setBlockerUserId(blockerUserId);
        e.setBlockedUserId(blockedUserId);
        jpa.save(e);
    }

    @Override
    @Transactional
    public void unblock(String blockerUserId, String blockedUserId) {
        jpa.deletePair(blockerUserId, blockedUserId);
    }

    @Override
    public List<BlockedUserView> listBlocked(String blockerUserId) {
        return jpa.findByBlockerUserId(blockerUserId).stream()
                .map(b -> {
                    var u = userRepo.findById(b.getBlockedUserId()).orElse(null);
                    String name = (u != null && u.getDisplayName() != null)
                            ? u.getDisplayName() : "Member";
                    return new BlockedUserView(b.getBlockedUserId(), name, b.getCreatedAt());
                })
                .toList();
    }
}
