package com.pally.domain.module;

import com.pally.domain.content.OutputType;
import com.pally.domain.content.OutputValidator;
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
 *
 * <p>This is the module-content PERSIST BOUNDARY, so it is where the {@link OutputValidator}
 * seam sits: every item is routed through it before saveAll. In Phase 1 the validator is
 * pass-through (output unchanged); Phase 3 makes it drop/flag malformed items here, in one
 * place, for every persist path (generate, regenerate, append-prove).
 */
@Component
@RequiredArgsConstructor
public class ModuleWriter {

    private final LearningModuleRepository moduleRepository;
    private final ModuleContentItemRepository itemRepository;
    private final OutputValidator outputValidator;

    /** Persist a freshly generated module and its items atomically. */
    @Transactional
    public LearningModule saveModuleWithItems(LearningModule module, List<ModuleContentItem> items) {
        List<ModuleContentItem> valid = outputValidator.retainValid(items, OutputType.MODULE_ITEM);
        LearningModule saved = moduleRepository.save(module);
        itemRepository.saveAll(valid);
        return saved;
    }

    /** Replace all of a module's items (teacher-requested full regen) atomically. */
    @Transactional
    public void replaceItems(String moduleId, List<ModuleContentItem> items) {
        List<ModuleContentItem> valid = outputValidator.retainValid(items, OutputType.MODULE_ITEM);
        itemRepository.deleteByModuleId(moduleId);
        itemRepository.saveAll(valid);
    }

    /** Append PROVE items, ordering them after any already-persisted PROVE items. */
    @Transactional
    public List<ModuleContentItem> appendProveItems(String moduleId, List<ModuleContentItem> items) {
        List<ModuleContentItem> valid = outputValidator.retainValid(items, OutputType.MODULE_ITEM);
        int existing = itemRepository.countByModuleIdAndStage(moduleId, ModuleStage.PROVE.name());
        for (int i = 0; i < valid.size(); i++) {
            valid.get(i).setSortOrder(existing + i);
        }
        itemRepository.saveAll(valid);
        return valid;
    }
}
