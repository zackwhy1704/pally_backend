package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiQualityVerifier;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.port.WikiCompilerPort;
import com.pally.domain.module.ModuleContentGenerator;
import com.pally.infrastructure.ai.ClaudeApiClient;
import com.pally.infrastructure.ai.ClaudeFlashcardGenerator;
import com.pally.infrastructure.ai.ModelRouter;
import com.pally.domain.chat.HintTreeGenerator;
import com.pally.infrastructure.persistence.module.LearningModuleJpaRepository;
import com.pally.infrastructure.persistence.knowledge.WikiPageSourceJpaEntity;
import com.pally.infrastructure.persistence.knowledge.WikiPageSourceJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /// Max slug length — must match the wiki_pages.slug column bound (V92).
    private static final int SLUG_MAX = 160;

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
    private final ModuleContentGenerator moduleContentGenerator;
    private final LearningModuleJpaRepository learningModuleRepository;
    private final WikiQualityVerifier wikiQualityVerifier;
    /// Self-reference (Spring proxy) so the per-page write runs in its OWN
    /// REQUIRES_NEW transaction. A self-invocation would bypass the proxy and
    /// lose the transaction boundary, so we go through the provider.
    private final ObjectProvider<WikiPagePersistenceService> selfProvider;

    public record PersistOutcome(
            int created,
            int updated,
            List<String> pageTitles,
            List<String> producedSlugs,
            List<FailedPage> failedPages) {
        /// Back-compat for callers/tests that predate per-page failure reporting.
        public PersistOutcome(int created, int updated,
                              List<String> pageTitles, List<String> producedSlugs) {
            this(created, updated, pageTitles, producedSlugs, List.of());
        }
    }

    /// Result of persisting a single draft in its own transaction.
    public record WriteResult(boolean created, String title) {}

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
    public PersistOutcome persistDrafts(Avatar avatar,
                                        List<WikiCompilerPort.WikiPageDraft> drafts,
                                        List<KnowledgeFile> sourceFiles) {
        int created = 0;
        int updated = 0;
        List<String> pageTitles = new ArrayList<>();
        List<String> producedSlugs = new ArrayList<>();
        List<FailedPage> failedPages = new ArrayList<>();
        String avatarId = avatar.getId();

        for (WikiCompilerPort.WikiPageDraft draft : drafts) {
            // The LLM slug is unbounded; clamp ONCE to the column bound and use the
            // clamped value for both lookup and create, so a recompile of an
            // over-long slug matches the stored page instead of duplicating it.
            String slug = clampSlug(draft.slug());
            producedSlugs.add(slug);
            try {
                // Each page persists in its OWN transaction (REQUIRES_NEW): one
                // page's DataIntegrity (e.g. a value reaching a too-narrow column)
                // can't poison a shared transaction and discard every other page in
                // the batch. A failure is recorded with its slug + cause and we move
                // on — partial content is far better than a blanket 400.
                WriteResult r = selfProvider.getObject()
                        .writeSingleDraft(avatarId, slug, draft, sourceFiles);
                if (r.created()) created++; else updated++;
                pageTitles.add(r.title());
            } catch (Exception e) {
                String reason = rootCauseMessage(e);
                failedPages.add(new FailedPage(slug, reason));
                log.error("[Wiki] Page persist FAILED slug={} avatar={} reason={} — "
                        + "continuing with remaining pages", slug, avatarId, reason);
            }
        }

        // Post-compile quality verification: log quality scores for newly persisted pages.
        // Best-effort — never blocks the persist outcome.
        try {
            String subject = avatar.getSubject() != null ? avatar.getSubject().name() : "GENERAL";
            List<WikiPage> newPages = producedSlugs.stream()
                    .map(slug -> wikiRepository.findByAvatarIdAndSlug(avatarId, slug))
                    .filter(java.util.Optional::isPresent)
                    .map(java.util.Optional::get)
                    .toList();
            for (WikiPage page : newPages) {
                WikiQualityVerifier.VerificationResult vr = wikiQualityVerifier.verify(page, subject);
                // Persist the verifier's 0.0–1.0 score as the page's 0–100 quality
                // score so INFERRED pages get a real signal (feeds reviewState's
                // LOW_CONFIDENCE band). setQualityScore clamps to [0,100].
                page.setQualityScore((int) Math.round(vr.qualityScore() * 100));
                wikiRepository.save(page);
                if (!vr.issues().isEmpty()) {
                    log.warn("[WikiQuality] page={} score={} issues={}",
                            vr.pageSlug(), String.format("%.2f", vr.qualityScore()), vr.issues());
                }
            }
        } catch (Exception e) {
            log.warn("[WikiQuality] Post-compile verification failed (non-fatal): {}", e.getMessage());
        }

        // Dedup pass: fetch all ACTIVE pages for this avatar and merge near-duplicates.
        List<WikiPage> allActivePages = wikiRepository.findActiveByAvatarId(avatarId);
        deduplicatePages(avatarId, allActivePages);

        int totalPages = wikiRepository.countActiveByAvatarId(avatarId); // ACTIVE only — matches quiz/brain filter
        avatar.setWikiPageCount(totalPages);
        avatarRepository.save(avatar);

        // Post-persist: generate learning modules for new/updated wiki pages (best-effort)
        queueModuleGeneration(avatar, producedSlugs);

        if (!failedPages.isEmpty()) {
            log.warn("[Wiki] persistDrafts avatar={} — {} page(s) failed to persist: {}",
                    avatarId, failedPages.size(), failedPages);
        }
        return new PersistOutcome(created, updated, pageTitles,
                List.copyOf(producedSlugs), List.copyOf(failedPages));
    }

    /**
     * Persists ONE draft (create or update + its hint tree, flashcards, provenance)
     * in its OWN transaction. REQUIRES_NEW so a DataIntegrity here is isolated to
     * this page — {@link #persistDrafts} catches it and continues with the rest of
     * the batch instead of losing every page. Public so the Spring proxy applies
     * (called via {@code selfProvider}); a self-invocation would bypass it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WriteResult writeSingleDraft(String avatarId, String slug,
                                        WikiCompilerPort.WikiPageDraft draft,
                                        List<KnowledgeFile> sourceFiles) {
        var existing = wikiRepository.findByAvatarIdAndSlug(avatarId, slug);
        WikiPage savedPage;
        boolean created;
        if (existing.isPresent()) {
            WikiPage existingPage = existing.get();
            ConflictResult conflict = detectConflict(
                    existingPage.getContent(), draft.content());
            existingPage.updateContent(
                    draft.title(), draft.content(), WikiPage.Certainty.INFERRED);
            if (conflict.conflict()) {
                existingPage.markConflict();
                if (conflict.note() != null) {
                    existingPage.setConflictNote(conflict.note());
                }
                log.warn("[Wiki] Conflict flagged on slug={} for avatar={} note={}",
                        slug, avatarId, conflict.note());
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
            created = false;
        } else {
            WikiPage newPage = WikiPage.create(
                    avatarId, slug, draft.title(), draft.content());
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
            created = true;
        }
        // Fix 3: Write provenance rows — replace on every recompile so they
        // stay in sync with the current READY file set.
        writeProvenanceRows(savedPage.getId(), sourceFiles);
        return new WriteResult(created, draft.title());
    }

    /// Most-specific cause message for a per-page failure — for a DataIntegrity this
    /// includes the constraint/column that rejected the write, so the failure is
    /// attributable in the failedPages report (e.g. "...conflict_note...").
    private static String rootCauseMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return cur.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }

    /**
     * Backwards-compat overload — used by callers that don't need provenance
     * (tests, legacy code paths). Delegates to the canonical method with an
     * empty source list. Not transactional: per-page writes own their transactions
     * (see {@link #writeSingleDraft}); the orchestrator stays resilient.
     */
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

    /// Outcome of conflict detection: the boolean verdict plus an optional
    /// short human-readable reason (only populated on the Haiku gray-band path,
    /// which is the only path that produces an explanation). The lexical-only
    /// block path leaves {@code note} null.
    record ConflictResult(boolean conflict, String note) {
        static final ConflictResult NONE = new ConflictResult(false, null);
        static ConflictResult conflict(String note) { return new ConflictResult(true, note); }
    }

    /// Two-stage conflict detection (B-B3):
    ///  1. Jaccard on lowercased word tokens (cheap, always runs).
    ///  2. If similarity is in the gray band, one Haiku yes/no — catches
    ///     paraphrase-vs-contradiction cases like "boils at 100°C" vs
    ///     "boils at 90°C" that lexical alone would miss/false-fire on.
    /// Only runs on slug collisions, so the LLM cost stays negligible.
    ConflictResult detectConflict(String existingContent, String newContent) {
        if (existingContent == null || newContent == null) return ConflictResult.NONE;
        Set<String> a = tokenize(existingContent);
        Set<String> b = tokenize(newContent);
        if (a.isEmpty() || b.isEmpty()) return ConflictResult.NONE;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        double jaccard = (double) intersection.size() / union.size();
        if (jaccard >= CONFLICT_GRAY_BELOW) {
            // The high-overlap band used to auto-pass — but a single factual flip
            // (a number/unit/date/proper-noun swapped IN MATCHING CONTEXT) keeps
            // Jaccard high while being a real contradiction, e.g. "38 ATP" vs
            // "36 ATP" (≈0.83). That is exactly the contradiction class that matters
            // most and the one a token-set similarity is blindest to. A deterministic
            // fact diff catches it precisely, where an LLM equality judge is
            // unreliable. Genuine prose-level disagreements still fall to the
            // gray-band Haiku below; this only adds an exact pre-check on this band.
            String factNote = detectFactConflict(existingContent, newContent);
            if (factNote != null) {
                log.info("[Wiki] Deterministic fact conflict (jaccard={}): {}",
                        String.format("%.2f", jaccard), factNote);
                return ConflictResult.conflict(factNote);
            }
            return ConflictResult.NONE;
        }
        // Lexical block band: too little overlap for the two passages to be the
        // same fact. No Haiku reason available, so the note stays null.
        if (jaccard < CONFLICT_BLOCK_BELOW) return ConflictResult.conflict(null);
        return haikuContradicts(existingContent, newContent, jaccard);
    }

    private static final Pattern FACT_WORD = Pattern.compile("[A-Za-z]+|\\d+(?:[.,]\\d+)?");

    /// Deterministic fact-level diff for the high-overlap band: returns a concrete
    /// note (e.g. {@code "produces atp: 38 vs 36"}) when a number or proper noun
    /// appears in BOTH passages under the same surrounding context but with a
    /// DIFFERENT value — a real contradiction hidden inside otherwise-similar prose.
    /// Returns null otherwise: a paraphrase (different context) or a superset (added
    /// detail, no clashing value) stays null, so the fix doesn't manufacture false
    /// positives. Exact and reliable where an LLM is not (numeric/unit/date equality).
    static String detectFactConflict(String existing, String incoming) {
        Map<String, String> fa = factMap(existing);
        if (fa.isEmpty()) return null;
        Map<String, String> fb = factMap(incoming);
        for (Map.Entry<String, String> e : fa.entrySet()) {
            String other = fb.get(e.getKey());
            if (other != null && !other.equalsIgnoreCase(e.getValue())) {
                String ctx = e.getKey().replace(" | ", " ").trim();
                return (ctx.isEmpty() ? "value" : ctx) + ": " + e.getValue() + " vs " + other;
            }
        }
        return null;
    }

    /// Maps each fact (a number, or a mixed-case proper noun) to its lowercased
    /// context window — up to two preceding words plus the next word, numbers
    /// excluded from the context. Sentence-split so a capitalised sentence-START
    /// word isn't mistaken for a proper noun. A fact with no surrounding words is
    /// skipped (too weak a key to match reliably). First value per context wins.
    static Map<String, String> factMap(String text) {
        Map<String, String> map = new LinkedHashMap<>();
        if (text == null || text.isBlank()) return map;
        for (String sentence : text.split("(?<=[.!?])\\s+")) {
            List<String> words = new ArrayList<>();
            Matcher m = FACT_WORD.matcher(sentence);
            while (m.find()) words.add(m.group());
            for (int i = 0; i < words.size(); i++) {
                String w = words.get(i);
                boolean isNumber = w.matches("\\d+(?:[.,]\\d+)?");
                boolean isProperNoun = i > 0 && w.length() >= 2
                        && Character.isUpperCase(w.charAt(0))
                        && w.substring(1).chars().anyMatch(Character::isLowerCase);
                if (!isNumber && !isProperNoun) continue;
                String ctx = factContext(words, i);
                if (!ctx.isEmpty()) map.putIfAbsent(ctx, w);
            }
        }
        return map;
    }

    private static String factContext(List<String> words, int idx) {
        List<String> pre = new ArrayList<>();
        for (int j = idx - 1; j >= 0 && pre.size() < 2; j--) {
            if (!words.get(j).matches("\\d.*")) pre.add(0, words.get(j).toLowerCase());
        }
        String post = "";
        for (int j = idx + 1; j < words.size(); j++) {
            if (!words.get(j).matches("\\d.*")) { post = words.get(j).toLowerCase(); break; }
        }
        if (pre.isEmpty() && post.isEmpty()) return ""; // no context ⇒ unreliable key
        return String.join(" ", pre) + " | " + post;
    }

    private ConflictResult haikuContradicts(String existing, String incoming, double jaccard) {
        try {
            String prompt = """
                    You compare two passages about the same topic from a kids'
                    tutor knowledge base. Answer YES if they materially
                    contradict each other (different facts, numbers, definitions);
                    answer NO if they're paraphrases of the same fact, or one is
                    a superset of the other.

                    Start your reply with the single word YES or NO. If YES, add a
                    short reason after a dash, e.g. "YES — contradicts the earlier
                    definition of X".

                    PASSAGE A:
                    %s

                    PASSAGE B:
                    %s
                    """.formatted(truncate(existing, 1500), truncate(incoming, 1500));
            String response = claudeApiClient.complete(
                    modelRouter.forRelevanceCheck(), 64, prompt);
            String verdict = response == null ? "" : response.trim();
            boolean conflict = verdict.toUpperCase().startsWith("YES");
            log.info("[Wiki] Haiku conflict check jaccard={} → {}",
                    String.format("%.2f", jaccard), conflict ? "CONFLICT" : "ok");
            if (!conflict) return ConflictResult.NONE;
            return ConflictResult.conflict(extractConflictNote(verdict));
        } catch (Exception e) {
            // Don't block persistence on the AI check — fall back to the
            // lexical signal (gray band leans toward "probably ok").
            log.warn("[Wiki] Haiku conflict check failed; defaulting to no-conflict: {}",
                    e.getMessage());
            return ConflictResult.NONE;
        }
    }

    /// Strips the leading YES/NO verdict word and any separator (":", "-", "—",
    /// or whitespace) from a Haiku conflict verdict, returning the trimmed
    /// reason capped at 500 chars. Returns null when no reason follows.
    static String extractConflictNote(String verdict) {
        if (verdict == null) return null;
        String s = verdict.trim();
        // Drop the leading YES/NO (case-insensitive), if present.
        if (s.length() >= 3 && s.substring(0, 3).equalsIgnoreCase("YES")) {
            s = s.substring(3);
        } else if (s.length() >= 2 && s.substring(0, 2).equalsIgnoreCase("NO")) {
            s = s.substring(2);
        }
        // Strip a leading separator run: any mix of ":", "-", "—", "–", whitespace.
        s = s.replaceFirst("^[\\s:\\-—–]+", "").trim();
        if (s.isEmpty()) return null;
        // No length cap: conflict_note is TEXT (V93). The previous raw
        // substring(0, 500) could split a UTF-16 surrogate pair at the boundary,
        // yielding an unpaired surrogate that is invalid UTF-8 and fails the DB
        // write with an encoding error → DataIntegrity → intermittent 400.
        return s;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return codePointCount(s) > max ? clampToCodePoints(s, max) + "…" : s;
    }

    /// Truncate to at most {@code maxChars} Unicode characters WITHOUT splitting a
    /// surrogate pair. Postgres VARCHAR(n) counts characters (code points), and an
    /// unpaired surrogate is invalid UTF-8 that fails the write. A raw
    /// String.substring counts UTF-16 units, so it can both overshoot the column
    /// AND split a pair; this is the safe equivalent for any bounded write/clamp.
    static String clampToCodePoints(String s, int maxChars) {
        return com.pally.shared.util.TextClamp.toCodePoints(s, maxChars);
    }

    private static int codePointCount(String s) {
        return s.codePointCount(0, s.length());
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

    /**
     * Best-effort module generation for newly persisted wiki pages.
     * Skips pages that already have a module (idempotent).
     * Failures are logged but never roll back the wiki persist transaction.
     */
    private void queueModuleGeneration(Avatar avatar, List<String> producedSlugs) {
        for (String slug : producedSlugs) {
            try {
                if (learningModuleRepository
                        .findByAvatarIdAndWikiPageSlug(avatar.getId(), slug)
                        .isPresent()) {
                    continue; // already has a module
                }
                var page = wikiRepository.findByAvatarIdAndSlug(avatar.getId(), slug);
                if (page.isPresent()) {
                    moduleContentGenerator.generate(avatar, page.get());
                }
            } catch (Exception e) {
                log.warn("[Wiki] Module generation failed for slug={}: {}",
                        slug, e.getMessage());
            }
        }
    }

    /// Bound an LLM-produced slug to the wiki_pages.slug column so a pathologically
    /// long slug truncates instead of failing the whole compile with a value-too-long.
    /// Code-point-safe so it never overshoots the column or splits a surrogate pair.
    private static String clampSlug(String slug) {
        return clampToCodePoints(slug, SLUG_MAX);
    }
}
