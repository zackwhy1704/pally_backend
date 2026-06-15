package com.pally.domain.module;

import com.pally.domain.group.ClassGroupService;
import com.pally.infrastructure.persistence.group.GroupSystemPostJpaEntity;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MuddiestServiceTest {

    @Mock MuddiestVoteRepository voteRepo;
    @Mock LearningModuleRepository moduleRepo;
    @Mock ModuleContentItemRepository itemRepo;
    @Mock ModuleProgressRepository progressRepo;
    @Mock ClassGroupService classGroupService;

    @InjectMocks MuddiestService service;

    private static final String MODULE = "module-1";
    private static final String CLASS = "class-1";

    private LearningModule module(String classId) {
        LearningModule m = new LearningModule();
        m.setId(MODULE);
        m.setClassId(classId);
        m.setTitle("Photosynthesis");
        return m;
    }

    private ModuleContentItem itemWithConcept(String concept) {
        ModuleContentItem it = new ModuleContentItem();
        it.setId("it-" + concept);
        it.setModuleId(MODULE);
        it.setContentJson("{}");
        it.setAnswerJson("{\"targetConcept\":\"" + concept + "\"}");
        return it;
    }

    @Test
    void vote_rejectsConceptNotInModule() {
        when(moduleRepo.findById(MODULE)).thenReturn(Optional.of(module(CLASS)));
        when(itemRepo.findByModuleIdOrderBySortOrder(MODULE))
                .thenReturn(List.of(itemWithConcept("light-reaction")));

        assertThatThrownBy(() -> service.vote(MODULE, "u1", "not-a-concept"))
                .isInstanceOf(BusinessException.class);
        verify(voteRepo, never()).save(any());
    }

    @Test
    void vote_upsert_changesConceptOnRevote() {
        when(moduleRepo.findById(MODULE)).thenReturn(Optional.of(module(CLASS)));
        when(itemRepo.findByModuleIdOrderBySortOrder(MODULE))
                .thenReturn(List.of(itemWithConcept("light-reaction"), itemWithConcept("calvin-cycle")));
        MuddiestVote existing = new MuddiestVote();
        existing.setId("v1");
        existing.setModuleId(MODULE);
        existing.setUserId("u1");
        existing.setConceptId("light-reaction");
        when(voteRepo.findByModuleIdAndUserId(MODULE, "u1")).thenReturn(Optional.of(existing));
        when(progressRepo.countDistinctCompletersByStage(anyString(), anyString())).thenReturn(0L);

        service.vote(MODULE, "u1", "calvin-cycle");

        ArgumentCaptor<MuddiestVote> c = ArgumentCaptor.forClass(MuddiestVote.class);
        verify(voteRepo).save(c.capture());
        assertThat(c.getValue().getId()).isEqualTo("v1"); // same row → upsert
        assertThat(c.getValue().getConceptId()).isEqualTo("calvin-cycle");
    }

    @Test
    void aggregate_containsNoUserIds_onlyConceptCounts() {
        when(itemRepo.findByModuleIdOrderBySortOrder(MODULE))
                .thenReturn(List.of(itemWithConcept("light-reaction")));
        when(voteRepo.countByConcept(MODULE)).thenReturn(List.of(
                new Object[]{"light-reaction", 5L},
                new Object[]{"calvin-cycle", 2L}));

        List<Map<String, Object>> agg = service.aggregate(MODULE);

        // MANDATORY: every row exposes only conceptId / conceptLabel / count.
        for (Map<String, Object> row : agg) {
            assertThat(row.keySet()).containsExactlyInAnyOrder("conceptId", "conceptLabel", "count");
            assertThat(row).doesNotContainKey("userId");
            assertThat(row).doesNotContainKey("user_id");
        }
        assertThat(agg.get(0)).containsEntry("conceptId", "light-reaction").containsEntry("count", 5L);
    }

    @Test
    void thresholdReached_postsToClassGroup_at30Percent() {
        when(moduleRepo.findById(MODULE)).thenReturn(Optional.of(module(CLASS)));
        when(itemRepo.findByModuleIdOrderBySortOrder(MODULE))
                .thenReturn(List.of(itemWithConcept("calvin-cycle")));
        when(voteRepo.findByModuleIdAndUserId(MODULE, "u1")).thenReturn(Optional.empty());
        // 10 completers, top concept has 3 votes → 30% → post.
        when(progressRepo.countDistinctCompletersByStage(MODULE, "PROVE")).thenReturn(10L);
        when(voteRepo.countByConcept(MODULE)).thenReturn(List.<Object[]>of(new Object[]{"calvin-cycle", 3L}));

        service.vote(MODULE, "u1", "calvin-cycle");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(classGroupService).postSystemMessage(
                eq(CLASS), eq(GroupSystemPostJpaEntity.KIND_MUDDIEST), body.capture(), eq(MODULE));
        assertThat(body.getValue()).contains("3 of 10").contains("calvin-cycle");
    }

    @Test
    void belowThreshold_doesNotPost() {
        when(moduleRepo.findById(MODULE)).thenReturn(Optional.of(module(CLASS)));
        when(itemRepo.findByModuleIdOrderBySortOrder(MODULE))
                .thenReturn(List.of(itemWithConcept("calvin-cycle")));
        when(voteRepo.findByModuleIdAndUserId(MODULE, "u1")).thenReturn(Optional.empty());
        when(progressRepo.countDistinctCompletersByStage(MODULE, "PROVE")).thenReturn(10L);
        when(voteRepo.countByConcept(MODULE)).thenReturn(List.<Object[]>of(new Object[]{"calvin-cycle", 2L})); // 20%

        service.vote(MODULE, "u1", "calvin-cycle");

        verify(classGroupService, never()).postSystemMessage(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void personalModule_noClassId_neverPosts() {
        when(moduleRepo.findById(MODULE)).thenReturn(Optional.of(module(null)));
        when(itemRepo.findByModuleIdOrderBySortOrder(MODULE))
                .thenReturn(List.of(itemWithConcept("calvin-cycle")));
        when(voteRepo.findByModuleIdAndUserId(MODULE, "u1")).thenReturn(Optional.empty());

        service.vote(MODULE, "u1", "calvin-cycle");

        verify(classGroupService, never()).postSystemMessage(anyString(), anyString(), anyString(), anyString());
    }
}
