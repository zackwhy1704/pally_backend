package com.pally.infrastructure.persistence.progress;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;

@Entity
@Table(name = "study_plan_completions")
@IdClass(StudyPlanCompletionJpaEntity.PK.class)
@Getter
@Setter
@NoArgsConstructor
public class StudyPlanCompletionJpaEntity {

    @Id
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Id
    @Column(name = "task_key", nullable = false, length = 200)
    private String taskKey;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    public static class PK implements Serializable {
        private String userId;
        private String taskKey;

        public PK() {}

        public PK(String userId, String taskKey) {
            this.userId = userId;
            this.taskKey = taskKey;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return java.util.Objects.equals(userId, pk.userId)
                    && java.util.Objects.equals(taskKey, pk.taskKey);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(userId, taskKey);
        }
    }
}
