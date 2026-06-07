-- V57: Rename is_centre_avatar → centre_avatar to match AvatarJpaEntity.
--
-- V56 created the column as is_centre_avatar but the JPA entity maps it
-- as @Column(name = "centre_avatar"), causing Hibernate schema-validation
-- to fail on startup. This rename fixes the mismatch.
--
-- The partial index on is_centre_avatar is also recreated on centre_avatar.

DROP INDEX IF EXISTS idx_avatar_centre_enrolment;

ALTER TABLE avatars
    RENAME COLUMN is_centre_avatar TO centre_avatar;

CREATE INDEX IF NOT EXISTS idx_avatar_centre_enrolment
    ON avatars(centre_enrolment_id)
    WHERE centre_avatar = TRUE;
