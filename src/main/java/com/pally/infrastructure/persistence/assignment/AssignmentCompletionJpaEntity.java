package com.pally.infrastructure.persistence.assignment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "assignment_completion")
@Getter
@Setter
@NoArgsConstructor
public class AssignmentCompletionJpaEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_OVERDUE = "OVERDUE";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "assignment_id", nullable = false, length = 36)
    private String assignmentId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "score_summary_json", columnDefinition = "TEXT")
    private String scoreSummaryJson;
}
