package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.port.WikiCompilerPort;
import com.pally.infrastructure.ai.ClaudeFlashcardGenerator;
import com.pally.domain.chat.HintTreeGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Atomic persistence step of the wiki compile pipeline. Split out from
 * CompileWikiUseCase so the Claude compile call stays OUTSIDE the
 * transaction — the audit's "don't hold a DB transaction open across a
 * 60s AI call" rule. The drafts arrive already-generated; we only do
 * fast in-DB writes here.
 *
 * <p>Flashcard regeneration is best-effort inside the transaction and
 * silently absorbs failures so a single shaky regen never rolls back
 * an entire compile.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WikiPagePersistenceService {

    private static final double CONFLICT_SIMILARITY_THRESHOLD = 0.40;

    private final WikiRepository wikiRepository;
    private final AvatarRepository avatarRepository;
    private final HintTreeGenerator hintTreeGenerator;
    private final ClaudeFlashcardGenerator flashcardGenerator;

    public record PersistOutcome(
            int created,
            int updated,
            List<String> pageTitles) {}

    @Transactional
    public PersistOutcome persistDrafts(Avatar avatar,
                                        List<WikiCompilerPort.WikiPageDraft> drafts) {
        int created = 0;
        int updated = 0;
        List<String> pageTitles = new ArrayList<>();
        String avatarId = avatar.getId();

        for (WikiCompilerPort.WikiPageDraft draft : drafts) {
            var existing = wikiRepository.findByAvatarIdAndSlug(avatarId, draft.slug());
            if (existing.isPresent()) {
                WikiPage existingPage = existing.get();
                boolean conflict = detectConflict(
                        existingPage.getContent(), draft.content());
                existingPage.updateContent(
                        draft.title(), draft.content(), WikiPage.Certainty.INFERRED);
                if (conflict) {
                    existingPage.markConflict();
                    log.warn("[Wiki] Conflict flagged on slug={} for avatar={}",
                            draft.slug(), avatarId);
                }
                if (draft.prerequisites() != null
                        && !draft.prerequisites().isEmpty()) {
                    existingPage.setPrerequisiteSlugs(
                            String.join(",", draft.prerequisites()));
                }
                WikiPage savedPage = wikiRepository.save(existingPage);
                hintTreeGenerator.generateForPage(avatarId, savedPage);
                try {
                    flashcardGenerator.regenerateForPage(avatarId, savedPage);
                } catch (Exception e) {
                    log.warn("[Wiki] Flashcard regen failed slug={}: {}",
                            savedPage.getSlug(), e.getMessage());
                }
                updated++;
                pageTitles.add(draft.title());
            } else {
                WikiPage newPage = WikiPage.create(
                        avatarId, draft.slug(), draft.title(), draft.content());
                if (draft.prerequisites() != null
                        && !draft.prerequisites().isEmpty()) {
                    newPage.setPrerequisiteSlugs(
                            String.join(",", draft.prerequisites()));
                }
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

        return new PersistOutcome(created, updated, pageTitles);
    }

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
        // Match the pre-refactor tokenizer exactly so conflict scores
        // for the same content pair are identical to the prior behaviour.
        return new HashSet<>(Arrays.asList(
                s.toLowerCase().replaceAll("[^a-z0-9 ]", " ").trim().split("\\s+")));
    }
}
