package com.pally.domain.module;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Domain type mirroring {@code ModuleContentItemJpaEntity}.
 * No JPA annotations — persistence is handled by the adapter.
 */
@Getter
@Setter
@NoArgsConstructor
public class ModuleContentItem {

    private String id;
    private String moduleId;
    private String stage;
    private String type;
    private String contentJson;
    private String answerJson;
    private int sortOrder;
    private String tierRequired;
    private Instant createdAt;
    private String status = "LIVE";
}
