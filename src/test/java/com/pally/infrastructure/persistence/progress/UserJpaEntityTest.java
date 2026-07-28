package com.pally.infrastructure.persistence.progress;

import com.pally.domain.user.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V124 round-trip guard for the user's UI-chrome locale. preferred_locale must
 * survive entity → domain, and default to 'en' so existing accounts are
 * unaffected when the column lands. Independent of any avatar content_language.
 */
class UserJpaEntityTest {

    @Test
    void preferredLocale_defaultsToEn() {
        UserJpaEntity entity = UserJpaEntity.newUser("u-1");

        assertThat(entity.getPreferredLocale()).isEqualTo("en");
        assertThat(entity.toUserDomain().getPreferredLocale()).isEqualTo("en");
    }

    @Test
    void preferredLocale_roundTripsToDomain() {
        UserJpaEntity entity = UserJpaEntity.newUser("u-1");
        entity.setPreferredLocale("zh");

        User domain = entity.toUserDomain();

        assertThat(domain.getPreferredLocale()).isEqualTo("zh");
    }

    @Test
    void preferredLocale_nullOnEntity_readsAsEnInDomain() {
        // A pre-V124 row read before the default applies must never surface null.
        UserJpaEntity entity = UserJpaEntity.newUser("u-1");
        entity.setPreferredLocale(null);

        assertThat(entity.toUserDomain().getPreferredLocale()).isEqualTo("en");
    }
}
