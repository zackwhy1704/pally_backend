package com.pally.domain.onboard;

import com.pally.domain.auth.dto.AuthResponse;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.consent.ConsentService;
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
    private final ConsentService consentService;

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
        return execute(email, password, displayName, subject, level, role,
                birthYear, parentEmail, null);
    }

    /**
     * @param contentLanguage language the AI generates this avatar's content in
     *         ('en' | 'zh'). Optional; absent/blank defaults to 'en'. A present-but-
     *         unsupported value is rejected with 400 — same gate as
     *         {@code CreateAvatarUseCase}.
     *
     * <p>TEST-CONVENIENCE OVERLOAD ONLY — hardcodes {@code acceptedTerms=true}.
     * Not reachable from production: {@link com.pally.api.onboard.OnboardController}
     * always calls the 10-arg canonical overload below with the real value from
     * {@code QuickOnboardRequest.acceptedTerms()}. This overload exists so the
     * many existing avatarName/content_language tests (which aren't testing the
     * terms gate) don't all need updating just to pass a fixed `true`.
     */
    @Transactional
    public QuickOnboardResult execute(
            String email, String password, String displayName,
            Subject subject, String level, String role, Integer birthYear,
            String parentEmail, String contentLanguage
    ) {
        return execute(email, password, displayName, subject, level, role,
                birthYear, parentEmail, contentLanguage, true);
    }

    /**
     * Canonical overload — the one production actually calls.
     *
     * @param acceptedTerms MANDATORY affirmative Terms-of-Use acceptance. There is
     *         no default: {@code false} (or a caller that never got this far)
     *         rejects with 400 BEFORE the duplicate-email check even runs, so a
     *         request that will be refused anyway never leaks whether the email is
     *         taken. Recorded to the {@code consent_records} audit log (same table
     *         the PDPA self-consent/parent-consent flows use) only after the
     *         account + avatar are both successfully created, so a mid-transaction
     *         failure never leaves an orphaned consent row for a nonexistent user.
     */
    @Transactional
    public QuickOnboardResult execute(
            String email, String password, String displayName,
            Subject subject, String level, String role, Integer birthYear,
            String parentEmail, String contentLanguage, boolean acceptedTerms
    ) {
        if (!acceptedTerms) {
            throw new BusinessException(
                    "You must accept the Terms of Use to create an account", 400);
        }

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

        // Step 2: Create a MOCHI avatar with the given subject.
        // Null/blank contentLanguage silently defaults to 'en' (a creation-time
        // convenience default, not an explicit user action — unlike the PATCH
        // settings endpoint, which rejects a missing value). Present-but-unsupported
        // still 400s via the same SupportedLanguage.validate() gate CreateAvatarUseCase
        // uses.
        String resolvedLanguage = contentLanguage == null || contentLanguage.isBlank()
                ? "en"
                : com.pally.domain.i18n.SupportedLanguage.validate(contentLanguage);
        // The default avatar NAME is baked in at creation time and never re-localized
        // at render (unlike subject/level, which the client resolves at display time
        // via localizedSubject()) — so it must be composed in the right language HERE,
        // not left to hardcode "Mochi" in English under a zh UI.
        String avatarName = "zh".equals(resolvedLanguage)
                ? subject.labelZh() + "小伴"
                : subject.label() + " Mochi";
        Avatar avatar = Avatar.create(
                authResponse.userId(), avatarName, subject, CharacterType.MOCHI, level, null);
        avatar.setContentLanguage(resolvedLanguage);
        Avatar saved = avatarRepository.save(avatar);

        // Step 3: Record the Terms-of-Use acceptance to the audit log — same
        // consent_records table + CHECKBOX method the PDPA self-consent flow uses,
        // its own policy-version lineage (recordTermsAcceptance/TOS_POLICY_VERSION)
        // since a ToS revision and a PDPA-purposes revision are independent events.
        // Placed AFTER both saves succeed (still inside this method's transaction)
        // so a rollback of account/avatar creation can never leave a consent row
        // for a user that doesn't exist.
        consentService.recordTermsAcceptance(authResponse.userId());

        log.info("[Onboard] Quick onboard complete userId={} avatarId={}",
                authResponse.userId(), saved.getId());

        return new QuickOnboardResult(authResponse.token(), authResponse.userId(), saved.getId());
    }

    public record QuickOnboardResult(String token, String userId, String avatarId) {}
}
