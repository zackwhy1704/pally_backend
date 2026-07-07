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
import com.pally.domain.weakness.WeaknessStateStore.WeaknessState;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

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
    @Mock WeaknessStateStore stateStore;

    WeaknessProfileService service;

    private Avatar sourceAvatar() {
        Avatar a = Avatar.create("user-1", "My Maths", Subject.MATHS, CharacterType.MOCHI);
        return a;
    }

    @BeforeEach
    void setUp() {
        service = new WeaknessProfileService(avatarRepository, signalRepository,
                signalService, wikiCompiler, persistenceService, wikiRepository, stateStore);
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
    void onMasteryUpdated_isANoOpWhenFlagOff() {
        ReflectionTestUtils.setField(service, "enabled", false);
        service.onMasteryUpdated("user-1", "av-x");
        verifyNoInteractions(avatarRepository, signalRepository, stateStore, wikiCompiler);
    }

    @Test
    void onMasteryUpdated_debounce_skipsWhenWeakSetUnchanged() {
        ReflectionTestUtils.setField(service, "enabled", true);
        Avatar src = sourceAvatar();
        when(avatarRepository.findById(src.getId())).thenReturn(Optional.of(src));
        when(signalRepository.findTopicMastery(eq("user-1"), anyString()))
                .thenReturn(List.of(new TopicMastery("fractions", 0.2, 5)));
        when(signalService.weakSlugs(any())).thenReturn(List.of("fractions"));
        when(stateStore.find("user-1", Subject.MATHS))
                .thenReturn(Optional.of(new WeaknessState("fractions", "")));

        service.onMasteryUpdated("user-1", src.getId());

        // Unchanged weak-set → no recompile, no state write (the debounce guard).
        verify(stateStore, never()).upsert(any(), any(), any(), any());
        verify(wikiCompiler, never()).compileWithTier(any(), any(), any());
    }

    @Test
    void onMasteryUpdated_recompilesAndRecordsWins_whenWeakSetChanged() {
        ReflectionTestUtils.setField(service, "enabled", true);
        Avatar src = sourceAvatar();
        when(avatarRepository.findById(src.getId())).thenReturn(Optional.of(src));
        when(signalRepository.findTopicMastery(eq("user-1"), anyString()))
                .thenReturn(List.of(new TopicMastery("ratios", 0.3, 5)));
        when(signalService.weakSlugs(any())).thenReturn(List.of("ratios"));
        when(stateStore.find("user-1", Subject.MATHS))
                .thenReturn(Optional.of(new WeaknessState("fractions,ratios", "")));
        // rebuildFor path:
        when(signalService.renderReport(eq(Subject.MATHS), any())).thenReturn("weak: ratios");
        when(avatarRepository.findByUserId("user-1")).thenReturn(List.of());
        when(avatarRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(wikiRepository.findByAvatarId(anyString())).thenReturn(List.of());
        when(wikiCompiler.compileWithTier(any(), any(), any()))
                .thenReturn(new CompileOutput(List.of(
                        new WikiPageDraft("ratios", "Ratios", "body"))));

        service.onMasteryUpdated("user-1", src.getId());

        // fractions recovered (was weak, now not) → recorded as a win; recompiled.
        ArgumentCaptor<String> wins = ArgumentCaptor.forClass(String.class);
        verify(stateStore).upsert(eq("user-1"), eq(Subject.MATHS), eq("ratios"), wins.capture());
        assertThat(wins.getValue()).contains("fractions");
        verify(wikiCompiler).compileWithTier(any(), any(), any());
        verify(persistenceService).persistDrafts(any(), any(), any());
    }

    @Test
    void recentWins_returnsStoredWins_whenEnabled() {
        ReflectionTestUtils.setField(service, "enabled", true);
        when(stateStore.find("user-1", Subject.MATHS))
                .thenReturn(Optional.of(new WeaknessState("ratios", "fractions,decimals")));
        assertThat(service.recentWins("user-1", Subject.MATHS))
                .containsExactly("fractions", "decimals");
    }

    @Test
    void weaknessPages_areEmptyWhenFlagOff() {
        ReflectionTestUtils.setField(service, "enabled", false);
        assertThat(service.weaknessPagesFor("user-1", Subject.MATHS)).isEmpty();
        verifyNoInteractions(wikiRepository);
    }

    @Test
    void focusFor_reportsEnabledFlagAndEmptyListsWhenOff() {
        ReflectionTestUtils.setField(service, "enabled", false);
        var out = service.focusFor("user-1", Subject.MATHS);
        assertThat(out.get("enabled")).isEqualTo(false);
        assertThat((List<?>) out.get("focusAreas")).isEmpty();
        assertThat((List<?>) out.get("recentWins")).isEmpty();
    }

    // ── weakSlugsFor: the REAL per-student weakness signal (what should shape content) ──

    @Test
    void weakSlugsFor_parsesTheStoredCommaJoinedSlugs() {
        ReflectionTestUtils.setField(service, "enabled", true);
        when(stateStore.find("user-1", Subject.MATHS))
                .thenReturn(Optional.of(new WeaknessState("fractions,decimals-place", "")));
        assertThat(service.weakSlugsFor("user-1", Subject.MATHS))
                .containsExactly("fractions", "decimals-place");
    }

    @Test
    void weakSlugsFor_returnsEmptyWhenDisabled() {
        ReflectionTestUtils.setField(service, "enabled", false);
        assertThat(service.weakSlugsFor("user-1", Subject.MATHS)).isEmpty();
    }

    @Test
    void weakSlugsFor_returnsEmptyWhenNoProfileYet() {
        ReflectionTestUtils.setField(service, "enabled", true);
        when(stateStore.find("user-1", Subject.MATHS)).thenReturn(Optional.empty());
        assertThat(service.weakSlugsFor("user-1", Subject.MATHS)).isEmpty();
    }

    @Test
    void weakSlugsFor_blankSignatureIsEmpty_notAListWithOneBlankSlug() {
        ReflectionTestUtils.setField(service, "enabled", true);
        when(stateStore.find("user-1", Subject.MATHS))
                .thenReturn(Optional.of(new WeaknessState("", "")));
        assertThat(service.weakSlugsFor("user-1", Subject.MATHS)).isEmpty();
    }

    @Test
    void focusFor_showsRealWeakTopicsAsLabels_notAllPages() {
        // The fix: focusFor reads the live weakSlugs signal, not weaknessPagesFor (all pages).
        ReflectionTestUtils.setField(service, "enabled", true);
        when(stateStore.find("user-1", Subject.MATHS))
                .thenReturn(Optional.of(new WeaknessState("dividing-fractions,decimals", "")));
        var out = service.focusFor("user-1", Subject.MATHS);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> areas = (List<Map<String, Object>>) out.get("focusAreas");
        assertThat(areas).extracting(a -> a.get("title"))
                .containsExactly("Dividing Fractions", "Decimals");
    }
}
