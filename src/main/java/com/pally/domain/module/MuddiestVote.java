package com.pally.domain.module;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Domain type mirroring {@code MuddiestVoteJpaEntity}.
 * No JPA annotations — persistence is handled by the adapter.
 */
@Getter
@Setter
@NoArgsConstructor
public class MuddiestVote {

    private String id;
    private String moduleId;
    private String classId;
    private String conceptId;
    private String userId;
    private Instant createdAt = Instant.now();
}
