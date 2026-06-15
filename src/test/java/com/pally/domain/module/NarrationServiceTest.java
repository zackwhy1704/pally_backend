package com.pally.domain.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.knowledge.port.StoragePort;
import com.pally.domain.module.port.TtsPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NarrationServiceTest {

    @Mock private TtsPort ttsPort;
    @Mock private StoragePort storagePort;
    @Mock private ModuleNarrationRepository narrationRepo;
    @Mock private ModuleContentItemRepository itemRepo;

    private NarrationService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new NarrationService(ttsPort, storagePort, narrationRepo, itemRepo, objectMapper);
    }

    @Test
    void generateAsync_createsNarrationRecord_andReturnsId() {
        when(narrationRepo.findByModuleId("mod-1")).thenReturn(Optional.empty());
        when(narrationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // Background thread stub — may or may not be reached before test completes
        lenient().when(itemRepo.findByModuleIdAndStageOrderBySortOrder("mod-1", "LEARN"))
                .thenReturn(List.of());

        String narrationId = service.generateAsync("mod-1", "default");

        assertThat(narrationId).isNotNull().isNotBlank();

        ArgumentCaptor<ModuleNarration> captor =
                ArgumentCaptor.forClass(ModuleNarration.class);
        verify(narrationRepo).save(captor.capture());
        ModuleNarration saved = captor.getValue();
        assertThat(saved.getModuleId()).isEqualTo("mod-1");
        assertThat(saved.getStatus()).isEqualTo("GENERATING");
        assertThat(saved.getVoiceId()).isEqualTo("default");
    }

    @Test
    void generateAsync_existingReady_returnsExistingId() {
        ModuleNarration existing = new ModuleNarration();
        existing.setId("existing-id");
        existing.setModuleId("mod-1");
        existing.setStatus("READY");
        when(narrationRepo.findByModuleId("mod-1")).thenReturn(Optional.of(existing));

        String result = service.generateAsync("mod-1", "default");

        assertThat(result).isEqualTo("existing-id");
        verify(narrationRepo, never()).save(any());
    }

    @Test
    void generateAsync_existingGenerating_returnsExistingId() {
        ModuleNarration existing = new ModuleNarration();
        existing.setId("gen-id");
        existing.setModuleId("mod-1");
        existing.setStatus("GENERATING");
        when(narrationRepo.findByModuleId("mod-1")).thenReturn(Optional.of(existing));

        String result = service.generateAsync("mod-1", "voice-x");

        assertThat(result).isEqualTo("gen-id");
    }

    @Test
    void generateAsync_existingFailed_deletesAndRetries() {
        ModuleNarration failed = new ModuleNarration();
        failed.setId("fail-id");
        failed.setModuleId("mod-1");
        failed.setStatus("FAILED");
        when(narrationRepo.findByModuleId("mod-1")).thenReturn(Optional.of(failed));
        when(narrationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // Background thread stub — may or may not be reached before test completes
        lenient().when(itemRepo.findByModuleIdAndStageOrderBySortOrder("mod-1", "LEARN"))
                .thenReturn(List.of());

        String result = service.generateAsync("mod-1", "default");

        assertThat(result).isNotEqualTo("fail-id"); // new ID
        verify(narrationRepo).delete(failed);
    }

    @Test
    void get_existingModule_returnsNarration() {
        ModuleNarration entity = new ModuleNarration();
        entity.setId("narr-1");
        entity.setModuleId("mod-1");
        entity.setStatus("READY");
        when(narrationRepo.findByModuleId("mod-1")).thenReturn(Optional.of(entity));

        Optional<ModuleNarration> result = service.get("mod-1");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("narr-1");
    }

    @Test
    void get_noNarration_returnsEmpty() {
        when(narrationRepo.findByModuleId("mod-x")).thenReturn(Optional.empty());

        Optional<ModuleNarration> result = service.get("mod-x");

        assertThat(result).isEmpty();
    }

    @Test
    void extractNarrationHint_validJson_returnsHint() {
        String json = """
                {"title":"Fractions","body":"Parts of a whole","keyTerms":["fraction"],"narration_hint":"Think of a pizza!"}
                """;
        String hint = service.extractNarrationHint(json);
        assertThat(hint).isEqualTo("Think of a pizza!");
    }

    @Test
    void extractNarrationHint_noHint_returnsNull() {
        String json = """
                {"title":"Fractions","body":"Parts of a whole","keyTerms":["fraction"]}
                """;
        String hint = service.extractNarrationHint(json);
        assertThat(hint).isNull();
    }

    @Test
    void extractNarrationHint_invalidJson_returnsNull() {
        String hint = service.extractNarrationHint("not json at all");
        assertThat(hint).isNull();
    }
}
