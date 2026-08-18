package com.pally.domain.syllabus;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.Subject;
import com.pally.domain.module.LearningModule;
import com.pally.domain.module.LearningModuleRepository;
import com.pally.domain.module.ModuleContentGenerator;
import com.pally.domain.module.ModuleContentItem;
import com.pally.domain.module.ModuleContentItemRepository;
import com.pally.domain.syllabus.dto.PackBrowseView;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyllabusContentPackServiceTest {

    @Mock private SyllabusContentPackRepository packRepository;
    @Mock private SyllabusContentPackAliasRepository aliasRepository;
    @Mock private AvatarRepository avatarRepository;
    @Mock private ModuleContentGenerator moduleContentGenerator;
    @Mock private LearningModuleRepository learningModuleRepository;
    @Mock private ModuleContentItemRepository itemRepository;

    private SyllabusContentPackService service;

    @BeforeEach
    void setUp() {
        service = new SyllabusContentPackService(
                packRepository, aliasRepository, avatarRepository, moduleContentGenerator,
                learningModuleRepository, itemRepository);
    }

    private SyllabusContentPack pack(String id, String avatarId, String status) {
        return new SyllabusContentPack(id, "SG-G3-COMPUTING-7155", "Algorithms",
                avatarId, status, "Isaac CS + Teach Computing Curriculum (OGL v3.0)",
                "Algorithms & Problem-Solving", Instant.now());
    }

    private ModuleContentItem item(String id, String moduleId, String status) {
        ModuleContentItem i = new ModuleContentItem();
        i.setId(id);
        i.setModuleId(moduleId);
        i.setStatus(status);
        return i;
    }

    private LearningModule module(String id, String avatarId) {
        LearningModule m = new LearningModule();
        m.setId(id);
        m.setAvatarId(avatarId);
        return m;
    }

    private SyllabusContentPackAlias alias(String packId, String syllabusCode, String topicTag) {
        return new SyllabusContentPackAlias(
                "alias-" + syllabusCode, packId, syllabusCode, topicTag, Instant.now());
    }

    // ── resolveOrCreatePack ──────────────────────────────────────────────────

    @Test
    void resolveOrCreatePack_returnsExisting_whenAlreadyPresent() {
        SyllabusContentPack existing = pack("pack-1", "av-1", "DRAFT");
        when(packRepository.findBySyllabusCodeAndTopicTag("SG-G3-COMPUTING-7155", "Algorithms"))
                .thenReturn(Optional.of(existing));

        SyllabusContentPack result = service.resolveOrCreatePack(
                "SG-G3-COMPUTING-7155", "Algorithms", Subject.CODING, "note", "Algorithms & Problem-Solving");

        assertThat(result).isEqualTo(existing);
        verify(avatarRepository, never()).save(any());
    }

    @Test
    void resolveOrCreatePack_lostCreateRace_discardsOrphanAvatar_returnsWinner() {
        when(packRepository.findBySyllabusCodeAndTopicTag("SG-G3-COMPUTING-7155", "Algorithms"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(pack("pack-winner", "av-winner", "DRAFT")));
        when(avatarRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(packRepository.save(any())).thenThrow(new DataIntegrityViolationException("uq_syllabus_content_pack_code_topic"));

        SyllabusContentPack result = service.resolveOrCreatePack(
                "SG-G3-COMPUTING-7155", "Algorithms", Subject.CODING, "note", "Algorithms & Problem-Solving");

        assertThat(result.id()).isEqualTo("pack-winner");
        verify(avatarRepository).deleteById(anyString());
    }

    @Test
    void resolveOrCreatePack_blankSyllabusCode_rejected() {
        assertThatThrownBy(() -> service.resolveOrCreatePack(
                "", "Algorithms", Subject.CODING, "note", "Algorithms & Problem-Solving"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void resolveOrCreatePack_blankDisplayLabel_rejected() {
        // V130: displayLabel is the ONLY client-safe text for a pack — must be required,
        // never silently defaulted, so a pack can never end up with no student-facing title.
        assertThatThrownBy(() -> service.resolveOrCreatePack(
                "SG-G3-COMPUTING-7155", "Algorithms", Subject.CODING, "note", "  "))
                .isInstanceOf(BusinessException.class);
    }

    // ── approveItems: cross-pack scoping guard ──────────────────────────────

    @Test
    void approveItems_onlyApprovesItemsBelongingToThisPacksOwnModules() {
        SyllabusContentPack pack = pack("pack-1", "av-1", "DRAFT");
        when(packRepository.findById("pack-1")).thenReturn(Optional.of(pack));
        when(learningModuleRepository.findByAvatarId("av-1"))
                .thenReturn(List.of(module("mod-own", "av-1")));

        ModuleContentItem ownItem = item("item-own", "mod-own", "DRAFT");
        ModuleContentItem foreignItem = item("item-foreign", "mod-other-pack", "DRAFT");
        when(itemRepository.findById("item-own")).thenReturn(Optional.of(ownItem));
        when(itemRepository.findById("item-foreign")).thenReturn(Optional.of(foreignItem));

        int approved = service.approveItems("pack-1", List.of("item-own", "item-foreign"));

        assertThat(approved).isEqualTo(1);
        assertThat(ownItem.getStatus()).isEqualTo("LIVE");
        assertThat(foreignItem.getStatus()).isEqualTo("DRAFT"); // untouched — not this pack's module
        verify(itemRepository).save(ownItem);
        verify(itemRepository, never()).save(foreignItem);
    }

    @Test
    void approveItems_skipsItemsNotInDraft() {
        SyllabusContentPack pack = pack("pack-1", "av-1", "DRAFT");
        when(packRepository.findById("pack-1")).thenReturn(Optional.of(pack));
        when(learningModuleRepository.findByAvatarId("av-1"))
                .thenReturn(List.of(module("mod-own", "av-1")));
        ModuleContentItem alreadyLive = item("item-live", "mod-own", "LIVE");
        when(itemRepository.findById("item-live")).thenReturn(Optional.of(alreadyLive));

        int approved = service.approveItems("pack-1", List.of("item-live"));

        assertThat(approved).isZero();
        verify(itemRepository, never()).save(any());
    }

    // ── addAlias (Phase 2: cross-syllabus reuse) ────────────────────────────

    @Test
    void addAlias_savesAlias_scopedToExistingPack() {
        SyllabusContentPack pack = pack("pack-1", "av-1", "PUBLISHED");
        when(packRepository.findById("pack-1")).thenReturn(Optional.of(pack));
        when(aliasRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SyllabusContentPackAlias result = service.addAlias(
                "pack-1", "CAMBRIDGE-IGCSE-CS-0478", "Algorithm-Design-and-Problem-Solving");

        assertThat(result.packId()).isEqualTo("pack-1");
        assertThat(result.syllabusCode()).isEqualTo("CAMBRIDGE-IGCSE-CS-0478");
    }

    @Test
    void addAlias_missingPack_rejected() {
        when(packRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addAlias("missing", "CAMBRIDGE-IGCSE-CS-0478", "Algorithms"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void addAlias_duplicateSyllabusTopic_rejectedNotSilentlyDropped() {
        SyllabusContentPack pack = pack("pack-1", "av-1", "PUBLISHED");
        when(packRepository.findById("pack-1")).thenReturn(Optional.of(pack));
        when(aliasRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("uq_syllabus_content_pack_alias_code_topic"));

        assertThatThrownBy(() -> service.addAlias("pack-1", "CAMBRIDGE-IGCSE-CS-0478", "Algorithms"))
                .isInstanceOf(BusinessException.class);
    }

    // ── browsePublished: the dual-gate proof + alias-based discovery ────────

    @Test
    void browsePublished_excludesPack_whenAllItemsStillDraft() {
        SyllabusContentPack published = pack("pack-1", "av-1", "PUBLISHED");
        when(packRepository.findByPackStatus("PUBLISHED")).thenReturn(List.of(published));
        when(learningModuleRepository.findByAvatarId("av-1")).thenReturn(List.of(module("mod-1", "av-1")));
        when(itemRepository.findServableByModuleIdOrderBySortOrder("mod-1")).thenReturn(List.of());

        List<PackBrowseView> views = service.browsePublished(null);

        assertThat(views).isEmpty();
    }

    @Test
    void browsePublished_includesPack_onlyAfterItemsApprovedServable() {
        SyllabusContentPack published = pack("pack-1", "av-1", "PUBLISHED");
        when(packRepository.findByPackStatus("PUBLISHED")).thenReturn(List.of(published));
        when(learningModuleRepository.findByAvatarId("av-1")).thenReturn(List.of(module("mod-1", "av-1")));
        when(itemRepository.findServableByModuleIdOrderBySortOrder("mod-1"))
                .thenReturn(List.of(item("item-1", "mod-1", "LIVE")));

        List<PackBrowseView> views = service.browsePublished(null);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).packId()).isEqualTo("pack-1");
        assertThat(views.get(0).displayLabel()).isEqualTo("Algorithms & Problem-Solving");
        assertThat(views.get(0).servableItemCount()).isEqualTo(1);
    }

    @Test
    void browsePublished_neverReturnsDraftPack_regardlessOfItemStatus() {
        when(packRepository.findByPackStatus("PUBLISHED")).thenReturn(List.of());

        List<PackBrowseView> views = service.browsePublished(null);

        assertThat(views).isEmpty();
        verify(packRepository, never()).findByPackStatus(eq("DRAFT"));
    }

    @Test
    void browsePublished_filteredByNativeSyllabusCode_matches() {
        SyllabusContentPack published = pack("pack-1", "av-1", "PUBLISHED");
        when(packRepository.findByPackStatus("PUBLISHED")).thenReturn(List.of(published));
        when(learningModuleRepository.findByAvatarId("av-1")).thenReturn(List.of(module("mod-1", "av-1")));
        when(itemRepository.findServableByModuleIdOrderBySortOrder("mod-1"))
                .thenReturn(List.of(item("item-1", "mod-1", "LIVE")));

        List<PackBrowseView> views = service.browsePublished("SG-G3-COMPUTING-7155");

        assertThat(views).hasSize(1);
    }

    @Test
    void browsePublished_filteredByAliasSyllabusCode_matchesViaAlias_noSecondGeneration() {
        // The Phase 2 point: a G3 Computing pack tagged with a Cambridge IGCSE alias is
        // discoverable under BOTH syllabus codes, using the SAME underlying modules/items
        // — no second pack, no second generation call.
        SyllabusContentPack published = pack("pack-1", "av-1", "PUBLISHED");
        when(packRepository.findByPackStatus("PUBLISHED")).thenReturn(List.of(published));
        when(aliasRepository.findByPackId("pack-1"))
                .thenReturn(List.of(alias("pack-1", "CAMBRIDGE-IGCSE-CS-0478", "Algorithm-Design")));
        when(learningModuleRepository.findByAvatarId("av-1")).thenReturn(List.of(module("mod-1", "av-1")));
        when(itemRepository.findServableByModuleIdOrderBySortOrder("mod-1"))
                .thenReturn(List.of(item("item-1", "mod-1", "LIVE")));

        List<PackBrowseView> views = service.browsePublished("CAMBRIDGE-IGCSE-CS-0478");

        assertThat(views).hasSize(1);
        assertThat(views.get(0).packId()).isEqualTo("pack-1");
        verify(moduleContentGenerator, never()).generateAsPack(any(), any());
    }

    @Test
    void browsePublished_filteredByUnmatchedSyllabusCode_excludesPack() {
        SyllabusContentPack published = pack("pack-1", "av-1", "PUBLISHED");
        when(packRepository.findByPackStatus("PUBLISHED")).thenReturn(List.of(published));
        when(aliasRepository.findByPackId("pack-1")).thenReturn(List.of());

        List<PackBrowseView> views = service.browsePublished("AP-CS-A");

        assertThat(views).isEmpty();
        verify(learningModuleRepository, never()).findByAvatarId(anyString());
    }
}
