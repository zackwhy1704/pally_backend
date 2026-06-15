package com.pally.infrastructure.persistence.group;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GroupSystemPostRepositoryAdapterTest {

    @Mock
    GroupSystemPostJpaRepository jpa;

    @InjectMocks
    GroupSystemPostRepositoryAdapter adapter;

    @Test
    void save_persistsEntityWithAllFields_andReturnsGeneratedId() {
        String id = adapter.save("group-1", "MUDDIEST", "3 of 10 found topic tricky", "module-1");

        assertThat(id).isNotBlank();

        ArgumentCaptor<GroupSystemPostJpaEntity> captor =
                ArgumentCaptor.forClass(GroupSystemPostJpaEntity.class);
        verify(jpa).save(captor.capture());

        GroupSystemPostJpaEntity saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(id);
        assertThat(saved.getGroupId()).isEqualTo("group-1");
        assertThat(saved.getKind()).isEqualTo("MUDDIEST");
        assertThat(saved.getBody()).isEqualTo("3 of 10 found topic tricky");
        assertThat(saved.getRefId()).isEqualTo("module-1");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void save_nullRefId_doesNotFail() {
        String id = adapter.save("group-2", "ANSWERS_RELEASED", "Answers are out", null);

        assertThat(id).isNotBlank();
        ArgumentCaptor<GroupSystemPostJpaEntity> captor =
                ArgumentCaptor.forClass(GroupSystemPostJpaEntity.class);
        verify(jpa).save(captor.capture());
        assertThat(captor.getValue().getRefId()).isNull();
    }

    @Test
    void save_generatesUniqueIdPerCall() {
        String id1 = adapter.save("group-1", "CHALLENGE", "body1", null);
        String id2 = adapter.save("group-1", "CHALLENGE", "body2", null);

        assertThat(id1).isNotEqualTo(id2);
    }
}
