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
import com.pally.infrastructure.ai.ClaudeFlashcardGenerator;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.exception.WikiCompileException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Use case: compile an avatar's knowledge files into structured wiki pages via Claude.
 */
@Service
@RequiredArgsConstructor
public class CompileWikiUseCase {

    private static final Logger log = LoggerFactory.getLogger(CompileWikiUseCase.class);
    // Jaccard similarity threshold below which two same-slug pages are flagged
    // as conflicting. 0.40 means: if less than 40% of tokens overlap, treat as
    // contradictory. Cheap heuristic — avoids paying for an extra Claude call
    // on every collision. Upgrade path: replace with an LLM yes/no check.
    private static final double CONFLICT_SIMILARITY_THRESHOLD = 0.40;

    private final AvatarRepository avatarRepository;
    private final KnowledgeRepository knowledgeRepository;
    private final WikiRepository wikiRepository;
    private final WikiCompilerPort wikiCompiler;
    private final CacheInvalidationService cacheInvalidationService;
    private final CacheKeepAliveService cacheKeepAliveService;
    private final HintTreeGenerator hintTreeGenerator;
    private final ClaudeFlashcardGenerator flashcardGenerator;

    public record CompileResult(
            int pagesCreated,
            int pagesUpdated,
            List<String> pageTitles
    ) {}

    public CompileResult execute(String avatarId) {
        Avatar avatar = avatarRepository.findById(avatarId)
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));

        List<KnowledgeFile> readyFiles = knowledgeRepository.findByAvatarId(avatarId).stream()
                .filter(f -> f.getStatus() == KnowledgeFile.Status.READY)
                .toList();

        if (readyFiles.isEmpty()) {
            log.warn("No READY files found for avatarId={}, skipping wiki compile", avatarId);
            return new CompileResult(0, 0, List.of());
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
        List<String> pageTitles = new ArrayList<>();

        for (WikiCompilerPort.WikiPageDraft draft : drafts) {
            var existing = wikiRepository.findByAvatarIdAndSlug(avatarId, draft.slug());
            if (existing.isPresent()) {
                WikiPage existingPage = existing.get();
                // Slug collision: same topic already exists. Compare contents to
                // detect a possible contradiction before overwriting.
                boolean conflict = detectConflict(existingPage.getContent(), draft.content());
                existingPage.updateContent(
                        draft.title(), draft.content(), WikiPage.Certainty.INFERRED);
                if (conflict) {
                    existingPage.markConflict();
                    log.warn("[Wiki] Conflict flagged on slug={} for avatar={}",
                            draft.slug(), avatarId);
                }
                WikiPage savedPage = wikiRepository.save(existingPage);
                hintTreeGenerator.generateForPage(avatarId, savedPage);
                // Page changed → regenerate its flashcards so SRS reflects
                // the new content. Best-effort, never blocks the compile.
                try {
                    flashcardGenerator.regenerateForPage(avatarId, savedPage);
                } catch (Exception e) {
                    log.warn("[Wiki] Flashcard regen failed slug={}: {}",
                            savedPage.getSlug(), e.getMessage());
                }
                updated++;
                pageTitles.add(draft.title());
            } else {
                WikiPage newPage = WikiPage.create(avatarId, draft.slug(), draft.title(), draft.content());
                WikiPage savedPage = wikiRepository.save(newPage);
                hintTreeGenerator.generateForPage(avatarId, savedPage);
                try {
                    flashcardGenerator.generateAndSaveForPage(
                            avatarId, savedPage);
                } catch (Exception e) {
                    log.warn("[Wiki] Flashcard gen failed slug={}: {}",
                            savedPage.getSlug(), e.getMessage());
                }
                created++;
                pageTitles.add(draft.title());
            }
        }

        int totalPages = wikiRepository.countByAvatarId(avatarId);
        avatar.setWikiPageCount(totalPages);
        avatarRepository.save(avatar);

        log.info("Wiki compiled for avatarId={} created={} updated={}", avatarId, created, updated);

        // Invalidate Block 3 cache so next request picks up the new content
        cacheInvalidationService.onWikiContentChanged(avatarId, cacheKeepAliveService);

        return new CompileResult(created, updated, pageTitles);
    }

    /**
     * Returns true when {@code newContent} appears to materially contradict
     * {@code existingContent}. Uses Jaccard similarity on lowercased word
     * tokens — anything below {@link #CONFLICT_SIMILARITY_THRESHOLD} is
     * treated as a conflict.
     */
    private boolean detectConflict(String existingContent, String newContent) {
        if (existingContent == null || newContent == null) return false;
        Set<String> a = tokenize(existingContent);
        Set<String> b = tokenize(newContent);
        if (a.isEmpty() || b.isEmpty()) return false;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        double jaccard = (double) intersection.size() / union.size();
        return jaccard < CONFLICT_SIMILARITY_THRESHOLD;
    }

    private Set<String> tokenize(String s) {
        return new HashSet<>(Arrays.asList(
                s.toLowerCase().replaceAll("[^a-z0-9 ]", " ").trim().split("\\s+")));
    }
}
