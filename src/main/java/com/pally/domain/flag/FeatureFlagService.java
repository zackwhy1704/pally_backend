package com.pally.domain.flag;

import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.config.AdminEmailService;
import com.pally.infrastructure.persistence.flag.UserFeatureFlagJpaEntity;
import com.pally.infrastructure.persistence.flag.UserFeatureFlagJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages per-user feature flags that drive pilot rollouts without client
 * redeploys. Absence of a row means the flag is off for that user.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FeatureFlagService {

    private final UserFeatureFlagJpaRepository flagRepo;
    private final UserRepository userRepository;
    private final AdminEmailService adminEmailService;

    /**
     * Returns every enabled flag for the given user, plus the derived
     * {@code is_admin} and {@code groups_enabled} synthetic flags.
     */
    @Transactional(readOnly = true)
    public Map<String, Boolean> getFlags(String userId) {
        Map<String, Boolean> flags = new HashMap<>();
        for (UserFeatureFlagJpaEntity row : flagRepo.findByUserId(userId)) {
            flags.put(row.getFlagName(), row.isEnabled());
        }

        // is_admin is derived EXCLUSIVELY from ADMIN_EMAILS env var at request time.
        // The DB role column is NOT consulted — this prevents any database manipulation
        // from granting admin access. Only updating ADMIN_EMAILS + redeploying works.
        String email = userRepository.findById(userId)
                .map(u -> u.getEmail())
                .orElse(null);
        boolean isAdmin = adminEmailService.isAdmin(email);
        flags.put("is_admin", isAdmin);

        // Groups are now open to all users — always return true
        flags.put("groups_enabled", true);
        return flags;
    }

    /**
     * Enables a named flag for a user. Creates the row if absent.
     * Returns a summary map for the API response.
     */
    @Transactional
    public Map<String, Object> enableFlag(String callerId, String targetUserId, String flagName) {
        UserFeatureFlagJpaEntity entity = flagRepo
                .findByUserIdAndFlagName(targetUserId, flagName)
                .orElseGet(UserFeatureFlagJpaEntity::new);
        entity.setUserId(targetUserId);
        entity.setFlagName(flagName);
        entity.setEnabled(true);
        entity.setUpdatedAt(Instant.now());
        flagRepo.save(entity);
        log.info("[Flags] caller={} enabled flag={} for user={}", callerId, flagName, targetUserId);
        return Map.of("userId", targetUserId, "flagName", flagName, "enabled", true);
    }

    /**
     * Disables a named flag for a user by removing the row.
     * Returns a summary map for the API response.
     */
    @Transactional
    public Map<String, Object> disableFlag(String callerId, String targetUserId, String flagName) {
        flagRepo.deleteByUserIdAndFlagName(targetUserId, flagName);
        log.info("[Flags] caller={} disabled flag={} for user={}", callerId, flagName, targetUserId);
        return Map.of("userId", targetUserId, "flagName", flagName, "enabled", false);
    }
}
