package com.pally.domain.module;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Short transactional writer for module content (A2).
 *
 * <p>Split out of {@link ModuleContentGenerator} so the slow (~30s, four sequential)
 * Gemini calls run with NO open transaction. Previously the whole generate method was
 * {@code @Transactional}, pinning a Hikari connection across all the AI work — a few
 * concurrent compiles exhausted the default pool of 10 and blocked every other request
 * (the "Apparent connection leak detected" WARNs). The generator now builds the module
 * + items in memory (the module's UUID is assigned at construction, so items can
 * reference it without a DB round-trip) and calls one of these to persist in a brief
 * transaction once the AI work is done.
 */
@Component
@RequiredArgsConstructor
public class ModuleWriter {

    private final LearningModuleRepository moduleRepository;
    private final ModuleContentItemRepository itemRepository;

    /** Persist a freshly generated module and its items atomically. */
    @Transactional
    public LearningModule saveModuleWithItems(LearningModule module, List<ModuleContentItem> items) {
        LearningModule saved = moduleRepository.save(module);
        itemRepository.saveAll(items);
        return saved;
    }

    /** Replace all of a module's items (teacher-requested full regen) atomically. */
    @Transactional
    public void replaceItems(String moduleId, List<ModuleContentItem> items) {
        itemRepository.deleteByModuleId(moduleId);
        itemRepository.saveAll(items);
    }

    /** Append PROVE items, ordering them after any already-persisted PROVE items. */
    @Transactional
    public List<ModuleContentItem> appendProveItems(String moduleId, List<ModuleContentItem> items) {
        int existing = itemRepository.countByModuleIdAndStage(moduleId, ModuleStage.PROVE.name());
        for (int i = 0; i < items.size(); i++) {
            items.get(i).setSortOrder(existing + i);
        }
        itemRepository.saveAll(items);
        return items;
    }
}
