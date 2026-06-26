package com.pally.domain.knowledge;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.infrastructure.persistence.knowledge.WikiConflictJpaEntity;
import com.pally.infrastructure.persistence.knowledge.WikiConflictJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Teacher-facing fact-conflict queue (Part A). Detection stays where it is; this is
 * the "then what": every detected conflict becomes a teacher review entry, and a
 * teacher's resolution is durable — the page is locked (a RESOLVED row for the slug)
 * so a later recompile that would change it opens a NEW entry instead of overwriting.
 *
 * <p>v1: the newest value stays LIVE for students (no quarantine). Quarantine of the
 * DETERMINISTIC tier can be layered on later without reworking this.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WikiConflictService {

    private final WikiConflictJpaRepository conflictRepo;
    private final WikiRepository wikiRepository;
    private final AvatarRepository avatarRepository;

    public record ConflictSummary(String id, String slug, String oldValue, String newValue,
                                  String note, String confidence, String status,
                                  Instant createdAt) {}

    /**
     * Opens a teacher review entry for a detected conflict. Idempotent per slug: if an
     * OPEN conflict already exists for this page we don't pile on duplicates each recompile.
     */
    @Transactional
    public void open(String avatarId, String slug, String oldValue, String newValue,
                     String note, String confidence) {
        if (conflictRepo.existsByAvatarIdAndSlugAndStatus(
                avatarId, slug, WikiConflictJpaEntity.STATUS_OPEN)) {
            return; // already queued for this page
        }
        WikiConflictJpaEntity c = new WikiConflictJpaEntity();
        c.setId(IdGenerator.newId());
        c.setAvatarId(avatarId);
        c.setSlug(slug);
        c.setOldValue(excerpt(oldValue));
        c.setNewValue(excerpt(newValue));
        c.setNote(note);
        c.setConfidence(normalizeConfidence(confidence));
        c.setStatus(WikiConflictJpaEntity.STATUS_OPEN);
        c.setCreatedAt(Instant.now());
        conflictRepo.save(c);
        log.info("[Conflict] OPEN avatar={} slug={} confidence={} note={}",
                avatarId, slug, c.getConfidence(), note);
    }

    /** True when the page has a teacher-RESOLVED conflict — i.e. it is locked. */
    public boolean isResolvedLocked(String avatarId, String slug) {
        return conflictRepo.existsByAvatarIdAndSlugAndStatus(
                avatarId, slug, WikiConflictJpaEntity.STATUS_RESOLVED);
    }

    /** Open conflicts for the teacher queue — DETERMINISTIC first. Owner-only. */
    @Transactional(readOnly = true)
    public List<ConflictSummary> listOpen(String avatarId, String userId) {
        requireOwner(avatarId, userId);
        return conflictRepo.findOpenForQueue(avatarId).stream()
                .map(c -> new ConflictSummary(c.getId(), c.getSlug(), c.getOldValue(),
                        c.getNewValue(), c.getNote(), c.getConfidence(), c.getStatus(),
                        c.getCreatedAt()))
                .toList();
    }

    /**
     * Teacher resolves a conflict: the chosen canonical content becomes the page's
     * content and the conflict is marked RESOLVED (which locks the page against silent
     * recompile overwrite). {@code canonicalValue} is the version the teacher kept.
     */
    @Transactional
    public void resolve(String avatarId, String conflictId, String userId, String canonicalValue) {
        requireOwner(avatarId, userId);
        WikiConflictJpaEntity c = conflictRepo.findById(conflictId)
                .filter(x -> x.getAvatarId().equals(avatarId))
                .orElseThrow(() -> new BusinessException("Conflict not found", 404));
        if (WikiConflictJpaEntity.STATUS_RESOLVED.equals(c.getStatus())) {
            return; // already resolved
        }
        if (canonicalValue != null && !canonicalValue.isBlank()) {
            wikiRepository.findByAvatarIdAndSlug(avatarId, c.getSlug()).ifPresent(page -> {
                page.applyResolution(canonicalValue);
                wikiRepository.save(page);
            });
        }
        c.setStatus(WikiConflictJpaEntity.STATUS_RESOLVED);
        c.setCanonicalValue(canonicalValue);
        c.setResolvedAt(Instant.now());
        c.setResolvedBy(userId);
        conflictRepo.save(c);
        log.info("[Conflict] RESOLVED id={} avatar={} slug={} by={}",
                conflictId, avatarId, c.getSlug(), userId);
    }

    private void requireOwner(String avatarId, String userId) {
        avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException("Avatar not found", 404));
    }

    private static String normalizeConfidence(String confidence) {
        return WikiConflictJpaEntity.CONFIDENCE_DETERMINISTIC.equals(confidence)
                ? WikiConflictJpaEntity.CONFIDENCE_DETERMINISTIC
                : WikiConflictJpaEntity.CONFIDENCE_PROSE;
    }

    private static String excerpt(String s) {
        if (s == null) return null;
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
