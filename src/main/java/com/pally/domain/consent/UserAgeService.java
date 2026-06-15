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
 *   <li>A null {@code birthYear} (pre-gate accounts, or a student who skipped
 *       the optional field) is treated as 13+ — never silently blocked.</li>
 * </ul>
 */
@Service
public class UserAgeService {

    /// Singapore is the operating jurisdiction for the PDPC age rule.
    static final ZoneId SG = ZoneId.of("Asia/Singapore");

    /// PDPC 2024 threshold: under-13 needs a parent/guardian; 13–17 self-consent.
    public static final int CONSENT_AGE = 13;

    /**
     * True only when the user has a birth year that places them strictly under 13
     * this calendar year (Singapore time). Null birth year → false (treated 13+).
     */
    public boolean isUnder13(User user) {
        if (user == null) {
            return false;
        }
        return isUnder13(user.getBirthYear());
    }

    /**
     * Year-only under-13 check. Null → false (conservative: not under 13).
     */
    public boolean isUnder13(Integer birthYear) {
        if (birthYear == null) {
            return false;
        }
        int currentYear = Year.now(SG).getValue();
        return (currentYear - birthYear) < CONSENT_AGE;
    }
}
