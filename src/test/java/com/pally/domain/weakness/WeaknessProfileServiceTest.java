package com.pally.domain.weakness;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarKind;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.port.WikiCompilerPort;
import com.pally.domain.knowledge.port.WikiCompilerPort.CompileOutput;
import com.pally.domain.knowledge.port.WikiCompilerPort.WikiPageDraft;
import com.pally.domain.knowledge.usecase.WikiPagePersistenceService;
import com.pally.domain.weakness.WeaknessSignalRepository.TopicMastery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WeaknessProfileServiceTest {

    @Mock AvatarRepository avatarRepository;
    @Mock WeaknessSignalRepository signalRepository;
    @Mock WeaknessSignalService signalService;
    @Mock WikiCompilerPort wikiCompiler;
    @Mock WikiPagePersistenceService persistenceService;
    @Mock WikiRepository wikiRepository;

    WeaknessProfileService service;

    private Avatar sourceAvatar() {
        Avatar a = Avatar.create("user-1", "My Maths", Subject.MATHS, CharacterType.MOCHI);
        return a;
    }

    @BeforeEach
    void setUp() {
        service = new WeaknessProfileService(avatarRepository, signalRepository,
                signalService, wikiCompiler, persistenceService, wikiRepository);
    }

    @Test
    void rebuild_isANoOpWhenFlagOff() {
        ReflectionTestUtils.setField(service, "enabled", false);

        service.rebuildFor(sourceAvatar());

        verifyNoInteractions(signalRepository, wikiCompiler, persistenceService);
    }

    @Test
    void rebuild_skipsWhenThereIsNoUsableSignal() {
        ReflectionTestUtils.setField(service, "enabled", true);
        when(signalRepository.findTopicMastery(anyString(), anyString()))
                .thenReturn(List.of());
        when(signalService.renderReport(any(), any())).thenReturn(null);

        service.rebuildFor(sourceAvatar());

        verify(wikiCompiler, never()).compileWithTier(any(), any(), any());
        verify(persistenceService, never()).persistDrafts(any(), any(), any());
    }

    @Test
    void rebuild_compilesAndPersistsToAWeaknessAvatar_creatingItWhenAbsent() {
        ReflectionTestUtils.setField(service, "enabled", true);
        when(signalRepository.findTopicMastery(eq("user-1"), anyString()))
                .thenReturn(List.of(new TopicMastery("fractions", 0.2, 5)));
        when(signalService.renderReport(eq(Subject.MATHS), any()))
                .thenReturn("weak: fractions");
        when(avatarRepository.findByUserId("user-1")).thenReturn(List.of()); // none yet
        when(avatarRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(wikiRepository.findByAvatarId(anyString())).thenReturn(List.of());
        when(wikiCompiler.compileWithTier(any(), any(), any()))
                .thenReturn(new CompileOutput(List.of(
                        new WikiPageDraft("fractions-division", "Fractions", "body"))));

        service.rebuildFor(sourceAvatar());

        // A WEAKNESS_PROFILE avatar was created and drafts persisted to it.
        ArgumentCaptor<Avatar> saved = ArgumentCaptor.forClass(Avatar.class);
        verify(avatarRepository).save(saved.capture());
        assertThat(saved.getValue().getKind()).isEqualTo(AvatarKind.WEAKNESS_PROFILE);
        verify(wikiCompiler).compileWithTier(any(), any(), any());
        verify(persistenceService).persistDrafts(any(), any(), any());
    }

    @Test
    void resolveOrCreate_reusesTheExistingWeaknessAvatar() {
        ReflectionTestUtils.setField(service, "enabled", true);
        Avatar existing = Avatar.create("user-1", "Maths Weakness Profile",
                Subject.MATHS, CharacterType.MOCHI);
        existing.markWeaknessProfile();
        when(avatarRepository.findByUserId("user-1")).thenReturn(List.of(existing));

        Avatar resolved = service.resolveOrCreate("user-1", Subject.MATHS);

        assertThat(resolved).isSameAs(existing);
        verify(avatarRepository, never()).save(any());
    }

    @Test
    void weaknessPages_areEmptyWhenFlagOff() {
        ReflectionTestUtils.setField(service, "enabled", false);
        assertThat(service.weaknessPagesFor("user-1", Subject.MATHS)).isEmpty();
        verifyNoInteractions(wikiRepository);
    }
}
