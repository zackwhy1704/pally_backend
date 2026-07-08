package com.pally.domain.onboard;

import com.pally.domain.auth.dto.AuthResponse;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.infrastructure.auth.AuthService;
import com.pally.infrastructure.auth.DuplicateSignupNotifier;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Combines REGISTER + avatar creation in a single call for fast onboarding.
 *
 * <p>Signup only. An already-registered email is REJECTED (409) — never logged in.
 * (This was previously a register-OR-login upsert, the account-takeover incident.)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuickOnboardService {

    private final AuthService authService;
    private final AvatarRepository avatarRepository;
    private final DuplicateSignupNotifier duplicateSignupNotifier;

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
        return execute(email, password, displayName, subject, level, null, null, null);
    }

    @Transactional
    public QuickOnboardResult execute(
            String email, String password, String displayName,
            Subject subject, String level, String role
    ) {
        return execute(email, password, displayName, subject, level, role, null, null);
    }

    @Transactional
    public QuickOnboardResult execute(
            String email, String password, String displayName,
            Subject subject, String level, String role, Integer birthYear
    ) {
        return execute(email, password, displayName, subject, level, role, birthYear, null);
    }

    @Transactional
    public QuickOnboardResult execute(
            String email, String password, String displayName,
            Subject subject, String level, String role, Integer birthYear,
            String parentEmail
    ) {
        // Step 1: Register or login.
        // Decide via a pre-check rather than catching register()'s 409. register()
        // is @Transactional and joins THIS transaction; a BusinessException thrown
        // across its boundary marks the shared transaction rollback-only, so even
        // though we "handle" the 409 the later commit fails with
        // UnexpectedRollbackException (a 500). Pre-checking keeps the happy path
        // exception-free so the commit succeeds.
        // INVARIANT (the account-takeover incident fix): a SIGNUP endpoint must NEVER
        // issue a token for a pre-existing account. This used to be a register-OR-login
        // upsert — an existing email was silently LOGGED IN (tokens issued for that
        // account), so "signup" behaved as "login". Now it 409s exactly like
        // /auth/register; the client shows "account exists — sign in instead". No
        // login-fallback, no token. execute() is the @Transactional root, so throwing
        // here rolls the whole onboard back cleanly (no avatar created).
        if (authService.emailExists(email)) {
            duplicateSignupNotifier.notifyOwner(email);
            throw new BusinessException("Email already registered", 409);
        }
        AuthResponse authResponse =
                authService.register(email, password, displayName, role, birthYear, parentEmail);
        log.info("[Onboard] New user registered via quick onboard userId={}", authResponse.userId());

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
