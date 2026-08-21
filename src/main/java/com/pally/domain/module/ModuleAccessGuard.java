package com.pally.domain.module;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.centre.CentreAccessService;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The ONE authorization check for reading a module's data as a student: the caller
 * either owns the module's avatar, or is an active member of the module's class.
 *
 * <p>Extracted from {@code ModuleProgressionService#assertModuleAccess} so a second
 * read surface (the mastery-audit read-model) cannot drift from it. Per CLAUDE.md,
 * authorisation goes through a guard service and is never re-implemented inline —
 * a copied 6-line auth check is exactly the "pattern applied to one instance, not
 * its family" failure mode this codebase keeps paying for.
 *
 * <p>404 (not 403) is deliberate and preserved from the original: it does not reveal
 * that another user's module exists.
 */
@Component
@RequiredArgsConstructor
public class ModuleAccessGuard {

    private final AvatarRepository avatarRepository;
    private final CentreAccessService centreAccessService;

    /** @throws BusinessException 404 when the caller may not read this module. */
    public void assertModuleAccess(LearningModule module, String userId) {
        boolean ownsAvatar = module.getAvatarId() != null
                && avatarRepository.existsByIdAndUserId(module.getAvatarId(), userId);
        boolean enrolledInClass = centreAccessService.isActiveClassMember(
                userId, module.getClassId());
        if (!ownsAvatar && !enrolledInClass) {
            throw new BusinessException("Module not found", 404);
        }
    }
}
