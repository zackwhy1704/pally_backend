package com.pally.infrastructure.persistence.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "study_groups")
@Getter
@Setter
@NoArgsConstructor
public class StudyGroupJpaEntity {

    /// Peer-created social study group (the original behaviour).
    public static final String TYPE_PEER = "PEER";
    /// Centre-owned group bound to a class; membership syncs from enrolment.
    public static final String TYPE_CLASS = "CLASS";

    @Id
    @Column(length = 36)
    private String id;

    /// PEER | CLASS. PEER groups behave exactly as before; CLASS groups are
    /// owned by a centre class and have synced, teacher-moderated membership.
    @Column(name = "group_type", nullable = false, length = 10)
    private String groupType = TYPE_PEER;

    /// Set only for CLASS groups: the org_class.id this group is bound to.
    @Column(name = "class_id", length = 36)
    private String classId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 30)
    private String subject;

    @Column(name = "invite_code", nullable = false, length = 12, unique = true)
    private String inviteCode;

    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
