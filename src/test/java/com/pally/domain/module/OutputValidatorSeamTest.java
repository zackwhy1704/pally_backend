package com.pally.domain.module;

import com.pally.domain.content.OutputType;
import com.pally.domain.content.OutputValidator;
import com.pally.domain.content.PassThroughOutputValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/// Phase 1 OutputValidator seam. Two guarantees:
///  1. the Phase-1 implementation is a strict PASS-THROUGH (output identical to before the
///     seam existed) — behavior-preservation;
///  2. ModuleWriter (the module-content persist boundary) actually ROUTES items through the
///     validator, so Phase 3 swapping in real rules takes effect at every persist path without
///     re-wiring.
@ExtendWith(MockitoExtension.class)
class OutputValidatorSeamTest {

    @Mock LearningModuleRepository moduleRepo;
    @Mock ModuleContentItemRepository itemRepo;

    @Test
    void passThrough_returnsEveryOutputUnchanged_forAllTypes() {
        OutputValidator v = new PassThroughOutputValidator();
        List<String> items = List.of("a", "b", "c");
        for (OutputType t : OutputType.values()) {
            assertThat(v.retainValid(items, t)).isSameAs(items);
        }
        assertThat(v.retainValid(List.of(), OutputType.MODULE_ITEM)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void moduleWriter_persistsOnlyWhatTheValidatorRetains() {
        // A validator that drops the last item — proves ModuleWriter consults the seam, so a
        // future real validator that omits a malformed item will actually keep it out of the DB.
        OutputValidator dropLast = new OutputValidator() {
            @Override
            public <T> List<T> retainValid(List<T> outputs, OutputType type) {
                return outputs.isEmpty() ? outputs : outputs.subList(0, outputs.size() - 1);
            }
        };
        ModuleWriter writer = new ModuleWriter(moduleRepo, itemRepo, dropLast);

        LearningModule module = new LearningModule();
        module.setId("m1");
        ModuleContentItem keep = new ModuleContentItem();
        ModuleContentItem dropped = new ModuleContentItem();
        writer.saveModuleWithItems(module, List.of(keep, dropped));

        ArgumentCaptor<List<ModuleContentItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepo).saveAll(captor.capture());
        assertThat(captor.getValue()).containsExactly(keep); // the validator's drop took effect
    }
}
