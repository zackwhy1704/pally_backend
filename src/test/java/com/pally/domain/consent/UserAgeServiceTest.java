package com.pally.domain.consent;

import com.pally.domain.user.User;
import org.junit.jupiter.api.Test;

import java.time.Year;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Server-side age derivation rule (PDPC 2024). Year-only, Singapore wall-clock.
 *
 * <p>Rule: {@code isUnder13 = (currentYear - birthYear) < 13}.
 * <ul>
 *   <li>Null birth year → NOT under-13 (pre-gate accounts treated as 13+).</li>
 *   <li>Boundary: a child who turns 13 this year (birthYear == currentYear - 13)
 *       gives exactly 13, which is NOT &lt; 13 → NOT under-13. Documented.</li>
 *   <li>birthYear == currentYear - 12 → 12 &lt; 13 → under-13.</li>
 * </ul>
 */
class UserAgeServiceTest {

    private final UserAgeService service = new UserAgeService();

    private int sgYear() {
        return Year.now(ZoneId.of("Asia/Singapore")).getValue();
    }

    @Test
    void nullBirthYear_failsClosed_treatedAsUnder13() {
        // INVERTED (compliance): unknown age = child until an age is collected.
        assertThat(service.isUnder13((Integer) null)).isTrue();
    }

    @Test
    void nullUser_failsClosed_treatedAsUnder13() {
        assertThat(service.isUnder13((User) null)).isTrue();
    }

    @Test
    void clearlyYoung_isUnder13() {
        // age == 10 this year → under 13
        assertThat(service.isUnder13(sgYear() - 10)).isTrue();
    }

    @Test
    void twelveByYearMath_isUnder13() {
        // currentYear - birthYear == 12 → 12 < 13 → under 13
        assertThat(service.isUnder13(sgYear() - 12)).isTrue();
    }

    @Test
    void exactlyThirteenByYearMath_isNotUnder13_boundary() {
        // currentYear - birthYear == 13 → NOT < 13 → 13+ (boundary documented)
        assertThat(service.isUnder13(sgYear() - 13)).isFalse();
    }

    @Test
    void clearlyOlder_isNotUnder13() {
        assertThat(service.isUnder13(sgYear() - 25)).isFalse();
    }

    @Test
    void isUnder13_readsFromUserBirthYear() {
        User child = new User();
        child.setBirthYear(sgYear() - 10);
        assertThat(service.isUnder13(child)).isTrue();

        User teen = new User();
        teen.setBirthYear(sgYear() - 15);
        assertThat(service.isUnder13(teen)).isFalse();
    }
}
