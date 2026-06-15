package com.pally.domain.consent;

import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.persistence.consent.ConsentRecordJpaRepository;
import com.pally.shared.exception.ConsentRequiredException;
import com.pally.shared.exception.GuardianRequiredException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Year;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the PDPC 2024 under-13 guardian gate
 * ({@link ConsentGuard#requireGuardianIfUnder13(String)}).
 *
 * <p>Invariants:
 * <ul>
 *   <li>Under-13 + no parent linked → 403 GuardianRequiredException (PARENT_LINK_REQUIRED).</li>
 *   <li>Under-13 + parent linked → allowed.</li>
 *   <li>13+ user → no-op (allowed) regardless of parent link.</li>
 *   <li>Null/unknown birth year → treated 13+ → allowed.</li>
 *   <li>Boundary: exactly-13-by-year-math → 13+ → allowed.</li>
 *   <li>User not found → no-op (fail open).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class GuardianGateTest {

    @Mock UserRepository userRepo;
    @Mock ConsentRecordJpaRepository consentRecordRepo;

    private static final String USER_ID = "kid-1";

    private ConsentGuard guard() {
        return new ConsentGuard(userRepo, consentRecordRepo, new UserAgeService());
    }

    private int sgYear() {
        return Year.now(ZoneId.of("Asia/Singapore")).getValue();
    }

    private User user(Integer birthYear, String parentId) {
        User u = new User();
        u.setId(USER_ID);
        u.setBirthYear(birthYear);
        u.setParentId(parentId);
        return u;
    }

    @Test
    void under13_noParentLinked_throwsParentLinkRequired() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user(sgYear() - 10, null)));

        assertThatThrownBy(() -> guard().requireGuardianIfUnder13(USER_ID))
                .isInstanceOf(GuardianRequiredException.class)
                .satisfies(ex -> assertThat(((ConsentRequiredException) ex).getReason())
                        .isEqualTo(ConsentGuard.REASON_PARENT_LINK_REQUIRED));
    }

    @Test
    void under13_blankParentId_throwsParentLinkRequired() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user(sgYear() - 10, "   ")));

        assertThatThrownBy(() -> guard().requireGuardianIfUnder13(USER_ID))
                .isInstanceOf(GuardianRequiredException.class);
    }

    @Test
    void under13_parentLinked_allowed() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user(sgYear() - 10, "parent-99")));

        assertThatCode(() -> guard().requireGuardianIfUnder13(USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void thirteenPlus_neverGated() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user(sgYear() - 16, null)));

        assertThatCode(() -> guard().requireGuardianIfUnder13(USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void nullBirthYear_treated13Plus_allowed() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user(null, null)));

        assertThatCode(() -> guard().requireGuardianIfUnder13(USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void exactly13ByYearMath_boundary_allowed() {
        // currentYear - birthYear == 13 → NOT under 13 → no gate
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user(sgYear() - 13, null)));

        assertThatCode(() -> guard().requireGuardianIfUnder13(USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void userNotFound_failsOpen() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> guard().requireGuardianIfUnder13(USER_ID))
                .doesNotThrowAnyException();
    }
}
