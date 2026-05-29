package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.port.WikiCompilerPort;
import com.pally.infrastructure.ai.ClaudeApiClient;
import com.pally.infrastructure.ai.ClaudeFlashcardGenerator;
import com.pally.infrastructure.ai.ModelRouter;
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

    /// Jaccard below this is a definite contradiction — too few words
    /// overlap for the two passages to be the same fact.
    private static final double CONFLICT_BLOCK_BELOW = 0.40;
    /// Jaccard in [BLOCK, GRAY) is the gray band — could be a paraphrase
    /// of the same fact OR a real contradiction; we ask Claude on these.
    private static final double CONFLICT_GRAY_BELOW = 0.70;

    private final WikiRepository wikiRepository;
    private final AvatarRepository avatarRepository;
    private final HintTreeGenerator hintTreeGenerator;
    private final ClaudeFlashcardGenerator flashcardGenerator;
    private final ClaudeApiClient claudeApiClient;
    private final ModelRouter modelRouter;

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

    /// Two-stage conflict detection (B-B3):
    ///  1. Jaccard on lowercased word tokens (cheap, always runs).
    ///  2. If similarity is in the gray band, one Haiku yes/no — catches
    ///     paraphrase-vs-contradiction cases like "boils at 100°C" vs
    ///     "boils at 90°C" that lexical alone would miss/false-fire on.
    /// Only runs on slug collisions, so the LLM cost stays negligible.
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
        if (jaccard >= CONFLICT_GRAY_BELOW) return false;
        if (jaccard < CONFLICT_BLOCK_BELOW) return true;
        return haikuContradicts(existingContent, newContent, jaccard);
    }

    private boolean haikuContradicts(String existing, String incoming, double jaccard) {
        try {
            String prompt = """
                    You compare two passages about the same topic from a kids'
                    tutor knowledge base. Answer YES if they materially
                    contradict each other (different facts, numbers, definitions);
                    answer NO if they're paraphrases of the same fact, or one is
                    a superset of the other.

                    Reply with ONLY the single word YES or NO.

                    PASSAGE A:
                    %s

                    PASSAGE B:
                    %s
                    """.formatted(truncate(existing, 1500), truncate(incoming, 1500));
            String response = claudeApiClient.complete(
                    modelRouter.forRelevanceCheck(), 8, prompt);
            String verdict = response == null ? "" : response.trim().toUpperCase();
            boolean conflict = verdict.startsWith("YES");
            log.info("[Wiki] Haiku conflict check jaccard={} → {}",
                    String.format("%.2f", jaccard), conflict ? "CONFLICT" : "ok");
            return conflict;
        } catch (Exception e) {
            // Don't block persistence on the AI check — fall back to the
            // lexical signal (gray band leans toward "probably ok").
            log.warn("[Wiki] Haiku conflict check failed; defaulting to no-conflict: {}",
                    e.getMessage());
            return false;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private Set<String> tokenize(String s) {
        // Match the pre-refactor tokenizer exactly so conflict scores
        // for the same content pair are identical to the prior behaviour.
        return new HashSet<>(Arrays.asList(
                s.toLowerCase().replaceAll("[^a-z0-9 ]", " ").trim().split("\\s+")));
    }
}
