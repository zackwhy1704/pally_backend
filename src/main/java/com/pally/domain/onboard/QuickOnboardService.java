package com.pally.domain.onboard;

import com.pally.domain.auth.dto.AuthResponse;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.infrastructure.auth.AuthService;
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
        // Step 1: Register or login.
        // Decide via a pre-check rather than catching register()'s 409. register()
        // is @Transactional and joins THIS transaction; a BusinessException thrown
        // across its boundary marks the shared transaction rollback-only, so even
        // though we "handle" the 409 the later commit fails with
        // UnexpectedRollbackException (a 500). Pre-checking keeps the happy path
        // exception-free so the commit succeeds.
        AuthResponse authResponse;
        if (authService.emailExists(email)) {
            // Already registered — log in with the same credentials.
            authResponse = authService.login(email, password);
            log.info("[Onboard] Existing user logged in via quick onboard userId={}", authResponse.userId());
        } else {
            authResponse = authService.register(email, password, displayName, role, birthYear);
            log.info("[Onboard] New user registered via quick onboard userId={}", authResponse.userId());
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
