package com.pally.domain.onboard;

import com.pally.api.auth.dto.AuthResponse;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.infrastructure.auth.AuthService;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Combines register/login + avatar creation in a single call for fast onboarding.
 *
 * <p>If the user already exists with the same email and password, treats it as a login
 * and creates the avatar anyway (unless they already have one for the same subject).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuickOnboardService {

    private final AuthService authService;
    private final AvatarRepository avatarRepository;

    /**
     * Quick onboard: register or login, then create a MOCHI avatar.
     *
     * @return result containing token, userId, and avatarId
     */
    @Transactional
    public QuickOnboardResult execute(
            String email, String password, String displayName,
            Subject subject, String level
    ) {
        return execute(email, password, displayName, subject, level, null, null);
    }

    @Transactional
    public QuickOnboardResult execute(
            String email, String password, String displayName,
            Subject subject, String level, String role
    ) {
        return execute(email, password, displayName, subject, level, role, null);
    }

    @Transactional
    public QuickOnboardResult execute(
            String email, String password, String displayName,
            Subject subject, String level, String role, Integer birthYear
    ) {
        // Step 1: Register or login
        AuthResponse authResponse;
        try {
            authResponse = authService.register(email, password, displayName, role, birthYear);
            log.info("[Onboard] New user registered via quick onboard userId={}", authResponse.userId());
        } catch (BusinessException e) {
            if (e.getHttpStatus() == 409) {
                // Already registered — try login with same credentials
                authResponse = authService.login(email, password);
                log.info("[Onboard] Existing user logged in via quick onboard userId={}", authResponse.userId());
            } else {
                throw e;
            }
        }

        // Step 2: Create a MOCHI avatar with the given subject
        String avatarName = subject.label() + " Mochi";
        Avatar avatar = Avatar.create(
                authResponse.userId(), avatarName, subject, CharacterType.MOCHI, level, null);
        Avatar saved = avatarRepository.save(avatar);

        log.info("[Onboard] Quick onboard complete userId={} avatarId={}",
                authResponse.userId(), saved.getId());

        return new QuickOnboardResult(authResponse.token(), authResponse.userId(), saved.getId());
    }

    public record QuickOnboardResult(String token, String userId, String avatarId) {}
}
