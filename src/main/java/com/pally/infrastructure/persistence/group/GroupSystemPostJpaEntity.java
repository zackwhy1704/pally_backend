package com.pally.infrastructure.persistence.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A backend-authored announcement in a group's feed (CLASS groups only in
 * practice). Unlike {@code group_shared_notes} it has no wiki page — it is
 * plain text, e.g. "Answers for ‹assignment› are out", a muddiest-point notice
 * or a challenge reveal. {@code refId} links back to the source entity
 * (assignment id, module id, challenge id) for client deep-linking.
 */
@Entity
@Table(name = "group_system_post")
@Getter
@Setter
@NoArgsConstructor
public class GroupSystemPostJpaEntity {

    public static final String KIND_ANSWERS_RELEASED = "ANSWERS_RELEASED";
    public static final String KIND_MUDDIEST = "MUDDIEST";
    public static final String KIND_CHALLENGE = "CHALLENGE";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "group_id", nullable = false, length = 36)
    private String groupId;

    @Column(nullable = false, length = 30)
    private String kind;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "ref_id", length = 36)
    private String refId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
