package com.pally.domain.knowledge;

import com.pally.infrastructure.persistence.knowledge.KnowledgeFileJpaEntity;
import com.pally.infrastructure.persistence.knowledge.KnowledgeFileJpaRepository;
import com.pally.shared.exception.DuplicateContentException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deduplication gate that prevents uploading identical or near-identical
 * content to the same avatar's knowledge base.
 *
 * <p>Two-level check — cheapest first:
 * <ol>
 *   <li><b>Exact hash</b>: SHA-256 of normalised text. Zero AI cost; catches
 *       the same file uploaded twice or re-scanned from the same source.</li>
 *   <li><b>Jaccard similarity</b>: token-set overlap between new text and
 *       each existing file's extracted text. Threshold {@link #SIMILAR_THRESHOLD}
 *       (default 0.75). Catches the same notes re-photographed at a different
 *       angle, or the same content saved as a different filename.</li>
 * </ol>
 *
 * <p>Both checks only deduplicate against content that's actually PRESENT in the
 * brain — i.e. status in {@link #PRESENT_STATUSES} (READY/PROCESSING/SEGMENTED/
 * PENDING_CHUNK). Files that were rejected (FAILED extraction, IRRELEVANT subject
 * mismatch) do NOT count as present, so re-uploading the same content after a
 * rejection (e.g. with skipRelevance) is allowed through, not blocked as a duplicate.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ContentDeduplicator {

    /**
     * Statuses that count as "content is actually in the brain" for dedup. An explicit
     * allow-list (not a NOT-equals on FAILED alone) so BOTH the exact-hash and Jaccard
     * checks exclude FAILED *and* IRRELEVANT identically, and a future terminal status
     * can't silently re-open the "re-upload of a rejected file → 409" bug.
     */
    static final Set<KnowledgeFile.Status> PRESENT_STATUSES = Set.of(
            KnowledgeFile.Status.READY,
            KnowledgeFile.Status.PROCESSING,
            KnowledgeFile.Status.SEGMENTED,
            KnowledgeFile.Status.PENDING_CHUNK);

    /** Jaccard similarity at or above which we consider files near-duplicates. */
    static final double SIMILAR_THRESHOLD = 0.75;

    /** Minimum token count for meaningful similarity — short files are skipped. */
    private static final int MIN_TOKENS_FOR_SIMILARITY = 30;

    private final KnowledgeFileJpaRepository fileRepo;

    /**
     * Checks new text against existing files for this avatar.
     * Throws {@link DuplicateContentException} if an exact or near-duplicate
     * is found; returns silently if the content is sufficiently different.
     *
     * @param avatarId    the avatar receiving the upload
     * @param newText     normalised extracted text of the new file
     * @param newFileName original filename (for error messages only)
     */
    public void check(String avatarId, String newText, String newFileName) {
        if (newText == null || newText.isBlank()) return;

        String hash = sha256(normalise(newText));

        // 1. Exact hash check — O(1) via indexed DB query. Only a PRESENT-status file
        // counts as a duplicate (excludes FAILED + IRRELEVANT), matching check 2 below.
        boolean exactExists = fileRepo.existsByAvatarIdAndContentHashAndStatusIn(
                avatarId, hash, PRESENT_STATUSES);
        if (exactExists) {
            // Find the original filename for a nicer error message
            String original = fileRepo.findByAvatarId(avatarId).stream()
                    .filter(f -> hash.equals(f.getContentHash())
                              && PRESENT_STATUSES.contains(f.getStatus()))
                    .map(KnowledgeFileJpaEntity::getFileName)
                    .findFirst()
                    .orElse("an existing file");
            log.info("[Dedup] Exact duplicate hash={} avatar={} original={}",
                    hash.substring(0, 8), avatarId, original);
            throw new DuplicateContentException(
                    DuplicateContentException.Kind.EXACT, original, 1.0);
        }

        // 2. Jaccard similarity against existing extracted texts
        Set<String> newTokens = tokenise(newText);
        if (newTokens.size() < MIN_TOKENS_FOR_SIMILARITY) return;

        List<KnowledgeFileJpaEntity> existing = fileRepo.findByAvatarId(avatarId);
        for (KnowledgeFileJpaEntity kf : existing) {
            if (!PRESENT_STATUSES.contains(kf.getStatus())) continue;
            if (kf.getExtractedText() == null || kf.getExtractedText().isBlank()) continue;

            Set<String> existingTokens = tokenise(kf.getExtractedText());
            if (existingTokens.size() < MIN_TOKENS_FOR_SIMILARITY) continue;

            double sim = jaccard(newTokens, existingTokens);
            if (sim >= SIMILAR_THRESHOLD) {
                log.info("[Dedup] Similar content sim={} avatar={} existing={}",
                        String.format("%.2f", sim), avatarId, kf.getFileName());
                throw new DuplicateContentException(
                        DuplicateContentException.Kind.SIMILAR, kf.getFileName(), sim);
            }
        }
    }

    /** Returns the SHA-256 hash to store alongside the file for future checks. */
    public String computeHash(String text) {
        return sha256(normalise(text));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Normalise: lowercase + collapse whitespace + strip non-alphanumeric. */
    static String normalise(String text) {
        return text.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static Set<String> tokenise(String text) {
        return new HashSet<>(Arrays.asList(normalise(text).split(" ")));
    }

    static double jaccard(Set<String> a, Set<String> b) {
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }
}
