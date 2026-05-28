package com.pally.infrastructure.persistence.group;

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
import java.util.Objects;

@Entity
@Table(name = "group_members")
@IdClass(GroupMemberJpaEntity.PK.class)
@Getter
@Setter
@NoArgsConstructor
public class GroupMemberJpaEntity {

    public static final String ROLE_OWNER = "OWNER";
    public static final String ROLE_MEMBER = "MEMBER";

    @Id
    @Column(name = "group_id", length = 36, nullable = false)
    private String groupId;

    @Id
    @Column(name = "user_id", length = 36, nullable = false)
    private String userId;

    @Column(nullable = false, length = 10)
    private String role = ROLE_MEMBER;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    public static class PK implements Serializable {
        private String groupId;
        private String userId;

        public PK() {}

        public PK(String groupId, String userId) {
            this.groupId = groupId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(groupId, pk.groupId)
                    && Objects.equals(userId, pk.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(groupId, userId);
        }
    }
}
