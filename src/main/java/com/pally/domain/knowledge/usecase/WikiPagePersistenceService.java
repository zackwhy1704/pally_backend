package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.port.WikiCompilerPort;
import com.pally.infrastructure.ai.ClaudeApiClient;
import com.pally.infrastructure.ai.ClaudeFlashcardGenerator;
import com.pally.infrastructure.ai.ModelRouter;
import com.pally.domain.chat.HintTreeGenerator;
import com.pally.infrastructure.persistence.knowledge.WikiPageSourceJpaEntity;
import com.pally.infrastructure.persistence.knowledge.WikiPageSourceJpaRepository;
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
    private final WikiPageSourceJpaRepository wikiPageSourceRepo;

    public record PersistOutcome(
            int created,
            int updated,
            List<String> pageTitles,
            List<String> producedSlugs) {}

    /**
     * Persists wiki page drafts from a compile run. Overwrites existing pages on
     * slug collision, creates new pages otherwise.
     *
     * <p>Fix 3: After saving each page, replaces any existing provenance rows in
     * {@code wiki_page_sources} with rows linking the page to all files in
     * {@code sourceFiles} (corpus-level attribution — every file in the compile
     * corpus is attributed to every page produced by that compile).
     *
     * @param sourceFiles the READY knowledge files that were fed into this compile
     *                    run. Pass an empty list to skip provenance writing (e.g.
     *                    in tests that don't need it).
     */
    @Transactional
    public PersistOutcome persistDrafts(Avatar avatar,
                                        List<WikiCompilerPort.WikiPageDraft> drafts,
                                        List<KnowledgeFile> sourceFiles) {
        int created = 0;
        int updated = 0;
        List<String> pageTitles = new ArrayList<>();
        List<String> producedSlugs = new ArrayList<>();
        String avatarId = avatar.getId();

        for (WikiCompilerPort.WikiPageDraft draft : drafts) {
            producedSlugs.add(draft.slug());
            var existing = wikiRepository.findByAvatarIdAndSlug(avatarId, draft.slug());
            WikiPage savedPage;
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
                savedPage = wikiRepository.save(existingPage);
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
                savedPage = wikiRepository.save(newPage);
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

            // Fix 3: Write provenance rows — replace on every recompile so they
            // stay in sync with the current READY file set.
            writeProvenanceRows(savedPage.getId(), sourceFiles);
        }

        // Dedup pass: fetch all ACTIVE pages for this avatar and merge near-duplicates.
        List<WikiPage> allActivePages = wikiRepository.findActiveByAvatarId(avatarId);
        deduplicatePages(avatarId, allActivePages);

        int totalPages = wikiRepository.countActiveByAvatarId(avatarId); // ACTIVE only — matches quiz/brain filter
        avatar.setWikiPageCount(totalPages);
        avatarRepository.save(avatar);

        return new PersistOutcome(created, updated, pageTitles, List.copyOf(producedSlugs));
    }

    /**
     * Backwards-compat overload — used by callers that don't need provenance
     * (tests, legacy code paths). Delegates to the canonical method with an
     * empty source list.
     */
    @Transactional
    public PersistOutcome persistDrafts(Avatar avatar,
                                        List<WikiCompilerPort.WikiPageDraft> drafts) {
        return persistDrafts(avatar, drafts, List.of());
    }

    /** Replace provenance rows for a single page (atomic delete-then-insert). */
    private void writeProvenanceRows(String wikiPageId, List<KnowledgeFile> sourceFiles) {
        if (sourceFiles.isEmpty()) return;
        try {
            wikiPageSourceRepo.deleteByWikiPageId(wikiPageId);
            List<WikiPageSourceJpaEntity> rows = sourceFiles.stream()
                    .map(f -> new WikiPageSourceJpaEntity(wikiPageId, f.getId()))
                    .toList();
            wikiPageSourceRepo.saveAll(rows);
        } catch (Exception e) {
            // Best-effort: provenance is non-critical — never roll back a compile for it.
            log.warn("[Wiki] Provenance write failed for wikiPageId={}: {}", wikiPageId, e.getMessage());
        }
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

    /**
     * Post-pass deduplication: detects near-duplicate wiki pages and merges them
     * by deleting the shorter/poorer page and keeping the richer one.
     *
     * <p>Two pages are near-duplicates when:
     * <ol>
     *   <li>Their slug token sets have Jaccard similarity ≥ 0.8 (very similar slugs), OR</li>
     *   <li>Their content word-sets have Jaccard similarity ≥ 0.75 (same text paraphrased).</li>
     * </ol>
     */
    void deduplicatePages(String avatarId, List<WikiPage> allPages) {
        if (allPages.size() < 2) return;
        List<WikiPage> pages = new ArrayList<>(allPages);
        Set<String> toDelete = new HashSet<>();

        for (int i = 0; i < pages.size(); i++) {
            if (toDelete.contains(pages.get(i).getId())) continue;
            for (int j = i + 1; j < pages.size(); j++) {
                if (toDelete.contains(pages.get(j).getId())) continue;
                WikiPage a = pages.get(i);
                WikiPage b = pages.get(j);
                if (areDuplicates(a, b)) {
                    // Keep the richer page (longer content wins)
                    WikiPage keep = a.getContent().length() >= b.getContent().length() ? a : b;
                    WikiPage drop = keep == a ? b : a;
                    toDelete.add(drop.getId());
                    log.info("[Dedup] Merged page '{}' into '{}' (avatarId={})",
                            drop.getSlug(), keep.getSlug(), avatarId);
                }
            }
        }

        if (!toDelete.isEmpty()) {
            toDelete.forEach(id -> wikiRepository.deleteById(id));
            log.info("[Dedup] Deleted {} near-duplicate pages for avatarId={}",
                    toDelete.size(), avatarId);
        }
    }

    /**
     * Returns true when two pages are near-duplicates by slug-token Jaccard (≥ 0.8)
     * or content word-set Jaccard (≥ 0.75).
     */
    boolean areDuplicates(WikiPage a, WikiPage b) {
        // Slug similarity: tokenize by "-" and check overlap
        Set<String> slugA = new HashSet<>(Arrays.asList(a.getSlug().split("-")));
        Set<String> slugB = new HashSet<>(Arrays.asList(b.getSlug().split("-")));
        Set<String> slugIntersection = new HashSet<>(slugA);
        slugIntersection.retainAll(slugB);
        Set<String> slugUnion = new HashSet<>(slugA);
        slugUnion.addAll(slugB);
        double slugJaccard = slugUnion.isEmpty() ? 0.0
                : (double) slugIntersection.size() / slugUnion.size();
        if (slugJaccard >= 0.8) return true;

        // Content word-set similarity
        Set<String> wordsA = tokenize(a.getContent());
        Set<String> wordsB = tokenize(b.getContent());
        if (wordsA.isEmpty() || wordsB.isEmpty()) return false;
        Set<String> contentIntersection = new HashSet<>(wordsA);
        contentIntersection.retainAll(wordsB);
        Set<String> contentUnion = new HashSet<>(wordsA);
        contentUnion.addAll(wordsB);
        return (double) contentIntersection.size() / contentUnion.size() >= 0.75;
    }
}
