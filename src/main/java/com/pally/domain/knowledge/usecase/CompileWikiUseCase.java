package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.chat.HintTreeGenerator;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.port.WikiCompilerPort;
import com.pally.infrastructure.ai.CacheInvalidationService;
import com.pally.infrastructure.ai.CacheKeepAliveService;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.exception.WikiCompileException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Use case: compile an avatar's knowledge files into structured wiki pages via Claude.
 */
@Service
@RequiredArgsConstructor
public class CompileWikiUseCase {

    private static final Logger log = LoggerFactory.getLogger(CompileWikiUseCase.class);

    private final AvatarRepository avatarRepository;
    private final KnowledgeRepository knowledgeRepository;
    private final WikiRepository wikiRepository;
    private final WikiCompilerPort wikiCompiler;
    private final CacheInvalidationService cacheInvalidationService;
    private final CacheKeepAliveService cacheKeepAliveService;
    private final HintTreeGenerator hintTreeGenerator;

    public record CompileResult(int pagesCreated, int pagesUpdated) {}

    public CompileResult execute(String avatarId) {
        Avatar avatar = avatarRepository.findById(avatarId)
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));

        List<KnowledgeFile> readyFiles = knowledgeRepository.findByAvatarId(avatarId).stream()
                .filter(f -> f.getStatus() == KnowledgeFile.Status.READY)
                .toList();

        if (readyFiles.isEmpty()) {
            log.warn("No READY files found for avatarId={}, skipping wiki compile", avatarId);
            return new CompileResult(0, 0);
        }

        log.info("Compiling wiki for avatarId={} from {} files", avatarId, readyFiles.size());

        List<WikiPage> existingPages = wikiRepository.findByAvatarId(avatarId);

        List<WikiCompilerPort.WikiPageDraft> drafts;
        try {
            drafts = wikiCompiler.compile(avatar, readyFiles, existingPages);
        } catch (Exception e) {
            throw new WikiCompileException("Wiki compilation failed for avatar " + avatarId, e);
        }

        int created = 0;
        int updated = 0;

        for (WikiCompilerPort.WikiPageDraft draft : drafts) {
            var existing = wikiRepository.findByAvatarIdAndSlug(avatarId, draft.slug());
            if (existing.isPresent()) {
                existing.get().updateContent(draft.title(), draft.content(), WikiPage.Certainty.INFERRED);
                WikiPage savedPage = wikiRepository.save(existing.get());
                hintTreeGenerator.generateForPage(avatarId, savedPage);
                updated++;
            } else {
                WikiPage newPage = WikiPage.create(avatarId, draft.slug(), draft.title(), draft.content());
                WikiPage savedPage = wikiRepository.save(newPage);
                hintTreeGenerator.generateForPage(avatarId, savedPage);
                created++;
            }
        }

        int totalPages = wikiRepository.countByAvatarId(avatarId);
        avatar.setWikiPageCount(totalPages);
        avatarRepository.save(avatar);

        log.info("Wiki compiled for avatarId={} created={} updated={}", avatarId, created, updated);

        // Invalidate Block 3 cache so next request picks up the new content
        cacheInvalidationService.onWikiContentChanged(avatarId, cacheKeepAliveService);

        return new CompileResult(created, updated);
    }
}
