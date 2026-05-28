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

    @Id
    @Column(length = 36)
    private String id;

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
