package com.pally.infrastructure.persistence.avatar;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip mapping guards. The centre flags + class linkage MUST survive
 * domain → entity → domain, or provisioned centre avatars persist as ordinary
 * personal avatars (no centre-mode UI, the deterministic closed-book gate never
 * fires, and removals never lock the avatar).
 */
class AvatarJpaEntityTest {

    @Test
    void fromDomain_persistsCentreFlagsAndClassLinkage() {
        Avatar avatar = Avatar.create("user-1", "P4 Math", Subject.MATHS, CharacterType.MOCHI);
        avatar.markCentreAvatar();
        avatar.lockAvatar();
        avatar.setClassId("class-1");
        avatar.setCorpusAvatarId("corpus-1");
        avatar.setCentreBrandName("ABC P4 Math");
        avatar.setCentreAccentColor("#7042ED");

        AvatarJpaEntity entity = AvatarJpaEntity.fromDomain(avatar);

        assertThat(entity.isCentreAvatar()).isTrue();
        assertThat(entity.isAvatarLocked()).isTrue();
        assertThat(entity.getClassId()).isEqualTo("class-1");
        assertThat(entity.getCorpusAvatarId()).isEqualTo("corpus-1");
        assertThat(entity.getCentreBrandName()).isEqualTo("ABC P4 Math");
    }

    @Test
    void toDomain_restoresCentreFlagsAndClassLinkage() {
        Avatar avatar = Avatar.create("user-1", "P4 Math", Subject.MATHS, CharacterType.MOCHI);
        avatar.markCentreAvatar();
        avatar.lockAvatar();
        avatar.setClassId("class-1");
        avatar.setCorpusAvatarId("corpus-1");

        Avatar roundTripped = AvatarJpaEntity.fromDomain(avatar).toDomain();

        assertThat(roundTripped.isCentreAvatar()).isTrue();
        assertThat(roundTripped.isAvatarLocked()).isTrue();
        assertThat(roundTripped.getClassId()).isEqualTo("class-1");
        assertThat(roundTripped.getCorpusAvatarId()).isEqualTo("corpus-1");
    }

    @Test
    void personalAvatar_staysNonCentre() {
        Avatar avatar = Avatar.create("user-1", "My Mochi", Subject.SCIENCE, CharacterType.MOCHI);

        AvatarJpaEntity entity = AvatarJpaEntity.fromDomain(avatar);

        assertThat(entity.isCentreAvatar()).isFalse();
        assertThat(entity.isAvatarLocked()).isFalse();
        assertThat(entity.getClassId()).isNull();
    }

    @Test
    void personalAvatar_kindDefaultsToPersonal_andRoundTrips() {
        Avatar avatar = Avatar.create("user-1", "My Mochi", Subject.SCIENCE, CharacterType.MOCHI);

        AvatarJpaEntity entity = AvatarJpaEntity.fromDomain(avatar);
        Avatar roundTripped = entity.toDomain();

        assertThat(entity.getKind()).isEqualTo(com.pally.domain.avatar.AvatarKind.PERSONAL);
        assertThat(roundTripped.getKind()).isEqualTo(com.pally.domain.avatar.AvatarKind.PERSONAL);
        assertThat(roundTripped.isCentreClass()).isFalse();
    }

    @Test
    void centreClassAvatar_kindRoundTrips_andSetsCentreFlag() {
        Avatar avatar = Avatar.create("user-1", "P4 Math", Subject.MATHS, CharacterType.MOCHI);
        avatar.markCentreClassAvatar();
        avatar.setClassId("class-1");

        Avatar roundTripped = AvatarJpaEntity.fromDomain(avatar).toDomain();

        assertThat(roundTripped.getKind()).isEqualTo(com.pally.domain.avatar.AvatarKind.CENTRE_CLASS);
        assertThat(roundTripped.isCentreClass()).isTrue();
        // markCentreClassAvatar must also flip the legacy centre flag so the
        // closed-book gate and lock behaviour still fire.
        assertThat(roundTripped.isCentreAvatar()).isTrue();
    }
}
