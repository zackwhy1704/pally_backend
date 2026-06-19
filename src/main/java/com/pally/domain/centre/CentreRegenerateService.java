package com.pally.domain.centre;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.module.LearningModule;
import com.pally.domain.module.LearningModuleRepository;
import com.pally.domain.module.ModuleContentGenerator;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Handles centre staff–initiated regeneration of module content for a specific wiki page.
 * Threads optional teacher guidance into the generation prompts so the model respects
 * pedagogical intent. New items enter as DRAFT and must pass the existing review queue.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CentreRegenerateService {

    private final CentreAccessService centreAccessService;
    private final OrgClassRepository orgClassRepository;
    private final WikiRepository wikiRepository;
    private final AvatarRepository avatarRepository;
    private final LearningModuleRepository moduleRepository;
    private final ModuleContentGenerator contentGenerator;
    private final RegenRateLimiter rateLimiter;

    /**
     * Regenerates all LEARN + TEST content items for the given wiki page on the class
     * corpus avatar. Existing items are deleted and fresh ones are created as DRAFT.
     *
     * @param guidance optional free-text teacher feedback; threaded into generation prompts
     * @return summary map with pageSlug, moduleId, and regenerated=true
     */
    @Transactional
    public Map<String, Object> regeneratePageContent(
            String userId, String orgId, String classId, String pageSlug, String guidance) {

        centreAccessService.ensureStaff(userId, orgId);

        String classOrgId = orgClassRepository.findOrganizationIdByClassId(classId)
                .orElseThrow(() -> new BusinessException("Class not found", 404));
        if (!orgId.equals(classOrgId)) {
            throw new BusinessException("Class does not belong to this organisation", 403);
        }

        // Rate-limit: 3 regenerations per page per 24 h (AI cost guard)
        rateLimiter.checkAndRecord(classId, pageSlug);

        String corpusAvatarId = orgClassRepository.findCorpusAvatarIdByClassId(classId)
                .orElseThrow(() -> new BusinessException(
                        "This class has no content yet — upload notes first.", 409));

        WikiPage page = wikiRepository.findByAvatarIdAndSlug(corpusAvatarId, pageSlug)
                .orElseThrow(() -> new BusinessException("Topic page not found: " + pageSlug, 404));

        Avatar avatar = avatarRepository.findById(corpusAvatarId)
                .orElseThrow(() -> new BusinessException("Content avatar not found", 404));

        LearningModule module = moduleRepository.findByAvatarIdAndWikiPageSlug(corpusAvatarId, pageSlug)
                .orElseThrow(() -> new BusinessException(
                        "No lesson module for this topic yet — build lessons first.", 409));

        contentGenerator.regenerateAsDraft(avatar, page, module, guidance);

        log.info("[CentreRegen] userId={} classId={} pageSlug={} guidanceLen={}",
                userId, classId, pageSlug, guidance != null ? guidance.length() : 0);

        return Map.of(
                "pageSlug", pageSlug,
                "moduleId", module.getId(),
                "regenerated", true
        );
    }
}
