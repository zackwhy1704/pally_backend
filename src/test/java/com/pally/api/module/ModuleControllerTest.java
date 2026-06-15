package com.pally.api.module;

import com.pally.domain.module.LearningModule;
import com.pally.domain.module.ModuleService;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ModuleControllerTest {

    @Mock private ModuleService moduleService;
    @InjectMocks private ModuleController controller;

    @Test
    void generateModules_returnsCreatedModules() {
        LearningModule module = buildModule("mod-1", "LEARN", "FREE");
        when(moduleService.generateModules("avatar-1")).thenReturn(List.of(module));

        var response = controller.generateModules("user-1", "avatar-1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).hasSize(1);
    }

    @Test
    void listModules_returnsModuleList() {
        when(moduleService.listModules("avatar-1"))
                .thenReturn(List.of(Map.of(
                        "id", "mod-1",
                        "title", "Fractions",
                        "stage", "LEARN")));

        var response = controller.listModules("user-1", "avatar-1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().data()).hasSize(1);
    }

    @Test
    void getModuleDetail_returnsDetail() {
        when(moduleService.getModuleDetail("mod-1", "user-1"))
                .thenReturn(Map.of("id", "mod-1", "title", "Fractions"));

        var response = controller.getModuleDetail("user-1", "avatar-1", "mod-1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().data().get("title")).isEqualTo("Fractions");
    }

    @Test
    void getModuleDetail_notFound_propagatesBusinessException() {
        when(moduleService.getModuleDetail("bad-id", "user-1"))
                .thenThrow(new BusinessException("Module not found", 404));

        assertThatThrownBy(() -> controller.getModuleDetail("user-1", "avatar-1", "bad-id"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void startModule_returnsStageItems() {
        when(moduleService.startModule("mod-1", "user-1"))
                .thenReturn(Map.of(
                        "moduleId", "mod-1",
                        "stage", "LEARN",
                        "items", List.of()));

        var response = controller.startModule("user-1", "avatar-1", "mod-1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().data().get("stage")).isEqualTo("LEARN");
    }

    @Test
    void submitAnswers_returnsResults() {
        var request = new com.pally.api.module.dto.SubmitModuleAnswersRequest(
                List.of(Map.of("itemId", "item-1", "response", "{}")), 90);

        when(moduleService.submitAnswers("mod-1", "user-1", request.submissions(), 90))
                .thenReturn(Map.of(
                        "results", List.of(Map.of("itemId", "item-1")),
                        "stageComplete", true,
                        "nextStage", "TEST"));

        var response = controller.submitAnswers("user-1", "avatar-1", "mod-1", request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().data().get("stageComplete")).isEqualTo(true);
    }

    @Test
    void getResults_returnsConceptMastery() {
        when(moduleService.getResults("mod-1", "user-1"))
                .thenReturn(Map.of(
                        "moduleId", "mod-1",
                        "masteryPct", BigDecimal.valueOf(85.0),
                        "conceptMastery", List.of()));

        var response = controller.getResults("user-1", "avatar-1", "mod-1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().data().get("moduleId")).isEqualTo("mod-1");
    }

    private LearningModule buildModule(String id, String stage, String tier) {
        LearningModule m = new LearningModule();
        m.setId(id);
        m.setAvatarId("avatar-1");
        m.setWikiPageSlug("fractions");
        m.setTitle("Fractions");
        m.setStage(stage);
        m.setTier(tier);
        m.setMasteryPct(BigDecimal.ZERO);
        m.setCreatedAt(Instant.now());
        return m;
    }
}
