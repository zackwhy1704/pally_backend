package com.pally.domain.marking;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarkingBrainServiceTest {

    @Mock private MarkingCorpusService markingCorpusService;
    @Mock private AvatarRepository avatarRepository;
    @Mock private WikiRepository wikiRepository;

    @InjectMocks private MarkingBrainService service;

    @Test
    void brainForClass_whenNoCorpusYet_returnsNotBuilt() {
        when(markingCorpusService.findAvatarIdForClass("class-1")).thenReturn(Optional.empty());

        Map<String, Object> brain = service.brainForClass("class-1");

        assertThat(brain.get("state")).isEqualTo("NOT_BUILT");
        assertThat(brain.get("pageCount")).isEqualTo(0);
        assertThat((List<?>) brain.get("pages")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void brainForClass_returnsCompiledPagesStateAndConflictFlag() {
        when(markingCorpusService.findAvatarIdForClass("class-1")).thenReturn(Optional.of("av-1"));

        Avatar avatar = mock(Avatar.class);
        when(avatar.getBrainState()).thenReturn(Avatar.BrainState.READY);
        when(avatar.getSubject()).thenReturn(Subject.MATHS);
        when(avatarRepository.findById("av-1")).thenReturn(Optional.of(avatar));

        WikiPage page = mock(WikiPage.class);
        when(page.getTitle()).thenReturn("How Method Marks Are Awarded");
        when(page.getSlug()).thenReturn("awarding-method-marks");
        when(page.getContent()).thenReturn("Award the method mark even if the answer is wrong.");
        when(page.getHumanCorrection()).thenReturn(null);
        when(page.getCertainty()).thenReturn(WikiPage.Certainty.INFERRED);
        when(page.isHasConflict()).thenReturn(true);
        when(wikiRepository.findActiveByAvatarId("av-1")).thenReturn(List.of(page));

        Map<String, Object> brain = service.brainForClass("class-1");

        assertThat(brain.get("state")).isEqualTo("READY");
        assertThat(brain.get("subject")).isEqualTo("Maths");
        assertThat(brain.get("pageCount")).isEqualTo(1);
        assertThat(brain.get("hasConflicts")).isEqualTo(true);
        List<Map<String, Object>> pages = (List<Map<String, Object>>) brain.get("pages");
        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).get("title")).isEqualTo("How Method Marks Are Awarded");
        assertThat(pages.get(0).get("slug")).isEqualTo("awarding-method-marks");
        assertThat(pages.get(0).get("hasConflict")).isEqualTo(true);
        assertThat((String) pages.get(0).get("preview")).contains("method mark");
    }
}
