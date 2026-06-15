package com.pally.domain.module;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Module GENERATION responsibility: builds learning modules from an avatar's
 * compiled wiki pages. Split out of the former god ModuleService.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModuleGenerationService {

    private final LearningModuleRepository moduleRepository;
    private final ModuleContentGenerator contentGenerator;
    private final AvatarRepository avatarRepository;
    private final WikiRepository wikiRepository;

    /**
     * Generate modules for all wiki pages of an avatar. Idempotent — skips
     * pages that already have a module.
     */
    @Transactional
    public List<LearningModule> generateModules(String avatarId) {
        Avatar avatar = avatarRepository.findById(avatarId)
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));

        List<WikiPage> pages = wikiRepository.findByAvatarId(avatarId);
        // No notes → no material to build lessons from. Fail loudly with a
        // structured code so the client routes the user to upload instead of
        // silently returning an empty success (the old dead-end behaviour).
        if (pages.isEmpty()) {
            throw new BusinessException("NO_NOTES", 409);
        }
        List<LearningModule> created = new ArrayList<>();

        for (WikiPage page : pages) {
            Optional<LearningModule> existing =
                    moduleRepository.findByAvatarIdAndWikiPageSlug(avatarId, page.getSlug());
            if (existing.isPresent()) {
                log.debug("[Module] Skipping existing module for slug={}", page.getSlug());
                continue;
            }

            try {
                LearningModule module = contentGenerator.generate(avatar, page);
                created.add(module);
            } catch (Exception e) {
                log.error("[Module] Failed to generate module for slug={}: {}",
                        page.getSlug(), e.getMessage());
            }
        }

        log.info("[Module] Generated {} new modules for avatar={}", created.size(), avatarId);
        return created;
    }
}
