package com.pally.infrastructure.persistence.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A student's membership of a class. Carries the id of the provisioned centre
 * avatar (the branded Mochi the student chats with for this class). See V59.
 */
@Entity
@Table(name = "class_membership")
@Getter
@Setter
@NoArgsConstructor
public class ClassMembershipJpaEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REMOVED = "REMOVED";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "class_id", nullable = false, length = 36)
    private String classId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "student_avatar_id", length = 36)
    private String studentAvatarId;

    @Column(nullable = false, length = 20)
    private String status = STATUS_ACTIVE;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt = Instant.now();
}
