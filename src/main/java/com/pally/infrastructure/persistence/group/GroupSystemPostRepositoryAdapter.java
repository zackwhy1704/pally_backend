package com.pally.infrastructure.persistence.group;

import com.pally.domain.group.GroupSystemPostRepository;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * JPA adapter implementing the {@link GroupSystemPostRepository} domain port.
 * Creates and persists {@link GroupSystemPostJpaEntity} records.
 */
@Component
@RequiredArgsConstructor
public class GroupSystemPostRepositoryAdapter implements GroupSystemPostRepository {

    private final GroupSystemPostJpaRepository jpa;

    @Override
    public String save(String groupId, String kind, String body, String refId) {
        GroupSystemPostJpaEntity p = new GroupSystemPostJpaEntity();
        p.setId(IdGenerator.newId());
        p.setGroupId(groupId);
        p.setKind(kind);
        p.setBody(body);
        p.setRefId(refId);
        p.setCreatedAt(Instant.now());
        jpa.save(p);
        return p.getId();
    }
}
