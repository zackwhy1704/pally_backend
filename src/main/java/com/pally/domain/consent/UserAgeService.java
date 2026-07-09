package com.pally.domain.consent;

import com.pally.domain.user.User;

import java.time.Year;
import java.time.ZoneId;
import org.springframework.stereotype.Service;

/**
 * Server-side age derivation for the PDPC 2024 age-conditional consent gate.
 *
 * <p>We store the birth YEAR only (data minimisation — no DOB, no NRIC). The
 * "is under 13" decision is made HERE, on the server, in Singapore wall-clock
 * time. A client-supplied age flag is never trusted.
 *
 * <p>Rule (conservative by year math):
 * {@code isUnder13 = (currentYear - birthYear) < 13}.
 * <ul>
 *   <li>A child who turns 13 at some point in the current year
 *       ({@code birthYear == currentYear - 13}) yields {@code 13}, which is
 *       NOT {@code < 13} → treated as 13+. They self-consent.</li>
 *   <li>A null/unknown {@code birthYear} is treated as UNDER-13 — fail-CLOSED.
 *       Unknown age = a child until proven otherwise (PDPC/COPPA-safe). The old
 *       fail-OPEN default (unknown = adult) was a compliance bug: every social
 *       sign-in and every birthYear-less register minted an adult account with no
 *       age check. Callers must collect an age (register + social) and re-prompt
 *       existing null accounts before consent-gated features.</li>
 * </ul>
 */
@Service
public class UserAgeService {

    /// Singapore is the operating jurisdiction for the PDPC age rule.
    static final ZoneId SG = ZoneId.of("Asia/Singapore");

    /// PDPC 2024 threshold: under-13 needs a parent/guardian; 13–17 self-consent.
    public static final int CONSENT_AGE = 13;

    /**
     * True when the user is under 13 this calendar year (Singapore time). A null
     * user or null birth year → TRUE (fail-CLOSED: unknown age = child).
     */
    public boolean isUnder13(User user) {
        if (user == null) {
            return true; // fail-closed — no user, no proof of age
        }
        // Age-exempt adults: PARENT (guardian) and ADULT (web centre-admin) are never
        // student data subjects, so the age gate does not apply — regardless of a null
        // birth year. Without this, the fail-closed null→under-13 default would wrongly
        // gate every adult web signup.
        if (user.getAccountType() == com.pally.domain.account.AccountType.PARENT
                || user.getAccountType() == com.pally.domain.account.AccountType.ADULT) {
            return false;
        }
        return isUnder13(user.getBirthYear());
    }

    /**
     * Year-only under-13 check. Null → TRUE (fail-CLOSED: unknown age = child until
     * an age is collected). Inverted from the old fail-open default (a compliance bug).
     */
    public boolean isUnder13(Integer birthYear) {
        if (birthYear == null) {
            return true;
        }
        int currentYear = Year.now(SG).getValue();
        return (currentYear - birthYear) < CONSENT_AGE;
    }
}
