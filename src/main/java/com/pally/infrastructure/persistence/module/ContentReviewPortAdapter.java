package com.pally.infrastructure.persistence.module;

import com.pally.domain.centre.ContentReviewPort;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JPA adapter implementing {@link ContentReviewPort}.
 * All DB access stays inside this class; the domain never sees JPA entities.
 */
@Component
@RequiredArgsConstructor
public class ContentReviewPortAdapter implements ContentReviewPort {

    private final ModuleContentItemJpaRepository jpa;
    private final LearningModuleJpaRepository moduleJpa;

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> findDraftItemsByClass(String classId) {
        // Scope to the class's own modules — never the whole table.
        return moduleJpa.findByClassId(classId).stream()
                .flatMap(m -> jpa.findByModuleIdOrderBySortOrder(m.getId()).stream())
                .filter(e -> "DRAFT".equals(e.getStatus()))
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ContentItemView findById(String itemId) {
        return jpa.findById(itemId)
                .map(this::toDomainView)
                .orElse(null);
    }

    @Override
    @Transactional
    public ContentItemView updateItem(UpdateItemCommand command) {
        ModuleContentItemJpaEntity item = jpa.findById(command.itemId())
                .orElseThrow(() -> new BusinessException("Content item not found", 404));

        // Cross-tenant guard: the item's module must belong to the caller's class.
        // 404 (not 403) so we don't reveal that another centre's item exists.
        LearningModuleJpaEntity module = moduleJpa.findById(item.getModuleId())
                .orElseThrow(() -> new BusinessException("Content item not found", 404));
        if (!command.classId().equals(module.getClassId())) {
            throw new BusinessException("Content item not found", 404);
        }

        if (command.newStatus() != null) {
            if ("REJECTED".equals(command.newStatus())) {
                jpa.delete(item);
                return null;
            }
            item.setStatus(command.newStatus());
        }

        if (command.contentJson() != null) {
            item.setContentJson(command.contentJson());
        }

        if (command.answerJson() != null) {
            item.setAnswerJson(command.answerJson());
        }

        return toDomainView(jpa.save(item));
    }

    @Override
    @Transactional
    public int approveItems(String classId, List<String> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) return 0;
        // Which modules belong to this class — the id must map into one of them or it's
        // skipped (cross-tenant guard: never approve another centre's item by id).
        java.util.Set<String> classModuleIds = moduleJpa.findByClassId(classId).stream()
                .map(LearningModuleJpaEntity::getId)
                .collect(java.util.stream.Collectors.toSet());
        int approved = 0;
        for (String id : itemIds) {
            ModuleContentItemJpaEntity item = jpa.findById(id).orElse(null);
            if (item == null || !classModuleIds.contains(item.getModuleId())) continue;
            if (!"DRAFT".equals(item.getStatus())) continue; // only DRAFT → LIVE
            item.setStatus("LIVE");
            jpa.save(item);
            approved++;
        }
        return approved;
    }

    // ── Mapping helpers ──────────────────────────────────────────────────────

    private ContentItemView toDomainView(ModuleContentItemJpaEntity e) {
        return new ContentItemView(
                e.getId(), e.getModuleId(), e.getStage(), e.getType(),
                e.getContentJson(), e.getAnswerJson(), e.getSortOrder(), e.getStatus(),
                e.getVerificationJson());
    }

    private Map<String, Object> toView(ModuleContentItemJpaEntity e) {
        // Enrich with module title + page slug so the UI can group correctly and
        // know which page to target for a regenerate call.
        LearningModuleJpaEntity mod = moduleJpa.findById(e.getModuleId()).orElse(null);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itemId", e.getId());        // matches ReviewItem.itemId in the web app
        m.put("moduleId", e.getModuleId());
        m.put("moduleTitle", mod != null ? mod.getTitle() : "Uncategorized");
        m.put("pageSlug", mod != null ? mod.getWikiPageSlug() : null);
        m.put("stage", e.getStage());
        m.put("type", e.getType());
        m.put("contentJson", e.getContentJson());
        m.put("answerJson", e.getAnswerJson());
        m.put("sortOrder", e.getSortOrder());
        m.put("status", e.getStatus());
        return m;
    }
}
