package com.pally.infrastructure.persistence.star;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "star_award_log")
@Getter
@Setter
@NoArgsConstructor
public class StarAwardLogJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "parent_id", nullable = false, length = 36)
    private String parentId;

    @Column(name = "child_id", nullable = false, length = 36)
    private String childId;

    @Column(nullable = false)
    private int amount;

    @Column(length = 255)
    private String note;

    @Column(name = "awarded_at", nullable = false)
    private Instant awardedAt;
}
