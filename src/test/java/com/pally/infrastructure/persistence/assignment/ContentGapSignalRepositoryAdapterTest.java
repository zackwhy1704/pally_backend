package com.pally.infrastructure.persistence.assignment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ContentGapSignalRepositoryAdapterTest {

    @Mock
    ContentGapSignalJpaRepository jpa;

    @InjectMocks
    ContentGapSignalRepositoryAdapter adapter;

    @Test
    void recordSignal_savesEntityWithAllFields() {
        adapter.recordSignal(
                "avatar-1", "class-1", "user-1",
                "What is photosynthesis?", BigDecimal.valueOf(0.42));

        ArgumentCaptor<ContentGapSignalJpaEntity> captor =
                ArgumentCaptor.forClass(ContentGapSignalJpaEntity.class);
        verify(jpa).save(captor.capture());

        ContentGapSignalJpaEntity saved = captor.getValue();
        assertThat(saved.getId()).isNotBlank();
        assertThat(saved.getClassId()).isEqualTo("class-1");
        assertThat(saved.getUserId()).isEqualTo("user-1");
        assertThat(saved.getQueryText()).isEqualTo("What is photosynthesis?");
        assertThat(saved.getMatchedConfidence()).isEqualByComparingTo("0.42");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void recordSignal_generatesUniqueIdPerCall() {
        adapter.recordSignal("avatar-1", "class-1", "user-1", "q", BigDecimal.ZERO);
        adapter.recordSignal("avatar-1", "class-1", "user-2", "q", BigDecimal.ZERO);

        ArgumentCaptor<ContentGapSignalJpaEntity> captor =
                ArgumentCaptor.forClass(ContentGapSignalJpaEntity.class);
        verify(jpa, org.mockito.Mockito.times(2)).save(captor.capture());

        var ids = captor.getAllValues().stream()
                .map(ContentGapSignalJpaEntity::getId)
                .toList();
        assertThat(ids.get(0)).isNotEqualTo(ids.get(1));
    }

    @Test
    void recordSignal_nullAvatarId_doesNotFailAndStillPersists() {
        // avatarId is accepted but not stored in the entity (no column for it);
        // this verifies no NPE is thrown and the save still happens.
        adapter.recordSignal(null, "class-1", "user-1", "query", BigDecimal.valueOf(0.1));

        verify(jpa).save(org.mockito.ArgumentMatchers.any(ContentGapSignalJpaEntity.class));
    }
}
