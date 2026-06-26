package com.pally.domain.module;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The short transactional writer extracted from ModuleContentGenerator (A2): it owns
 * the only DB writes, so the generator's four ~8s Gemini calls run with no open
 * transaction (no Hikari connection pinned across the AI work).
 */
@ExtendWith(MockitoExtension.class)
class ModuleWriterTest {

    @Mock LearningModuleRepository moduleRepo;
    @Mock ModuleContentItemRepository itemRepo;

    private ModuleWriter writer;

    @BeforeEach
    void setUp() {
        writer = new ModuleWriter(moduleRepo, itemRepo);
    }

    private static ModuleContentItem item(String id) {
        ModuleContentItem i = new ModuleContentItem();
        i.setId(id);
        return i;
    }

    @Test
    void saveModuleWithItems_persistsModuleThenItems() {
        LearningModule m = new LearningModule();
        m.setId("m1");
        when(moduleRepo.save(m)).thenReturn(m);
        List<ModuleContentItem> items = List.of(item("i1"), item("i2"));

        writer.saveModuleWithItems(m, items);

        verify(moduleRepo).save(m);
        verify(itemRepo).saveAll(items);
    }

    @Test
    void replaceItems_deletesExistingThenSaves() {
        List<ModuleContentItem> items = List.of(item("i1"));

        writer.replaceItems("m1", items);

        InOrder order = inOrder(itemRepo);
        order.verify(itemRepo).deleteByModuleId("m1");
        order.verify(itemRepo).saveAll(items);
    }

    @Test
    void appendProveItems_offsetsSortOrderByExistingProveCount() {
        when(itemRepo.countByModuleIdAndStage("m1", ModuleStage.PROVE.name())).thenReturn(2);
        ModuleContentItem a = item("a");
        ModuleContentItem b = item("b");

        writer.appendProveItems("m1", List.of(a, b));

        assertThat(a.getSortOrder()).isEqualTo(2);   // ordered after the 2 existing
        assertThat(b.getSortOrder()).isEqualTo(3);
        verify(itemRepo).saveAll(anyList());
    }
}
