package com.pally.domain.marking;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.centre.OrgClassRepository;
import com.pally.domain.organization.OrganizationRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Resolves (and lazily provisions) a centre's MARKING-STANDARD brain, keyed by
 * (orgId, subject). The brain is a hidden {@code AvatarKind.MARKING_CORPUS}
 * avatar whose knowledge files are compiled by the SAME wiki harness as student
 * notes — so the marking standard merges, dedups, detects conflicts, and decays
 * instead of piling up flat text. One marking brain per (org, subject); all the
 * org's teachers of that subject share and improve it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarkingCorpusService {

    private final MarkingCorpusRepository markingCorpusRepository;
    private final AvatarRepository avatarRepository;
    private final OrganizationRepository organizationRepository;
    private final OrgClassRepository orgClassRepository;

    /**
     * Returns the marking-corpus avatar id for (orgId, subject), creating the
     * hidden avatar + mapping on first use. Used by the ingest path.
     */
    public String resolveOrCreate(String orgId, Subject subject) {
        if (orgId == null || orgId.isBlank()) {
            throw new BusinessException("orgId is required", 400);
        }
        String subjectName = subject.name();

        Optional<MarkingCorpus> existing =
                markingCorpusRepository.findByOrgIdAndSubject(orgId, subjectName);
        if (existing.isPresent()) {
            return existing.get().avatarId();
        }

        String ownerUserId = organizationRepository.findOwnerUserIdById(orgId)
                .orElseThrow(() -> new BusinessException("Organization not found", 404));

        Avatar corpus = Avatar.create(
                ownerUserId,
                subject.label() + " Marking Standard",
                subject,
                CharacterType.MOCHI,
                null, null);
        corpus.markMarkingCorpus();
        Avatar savedAvatar = avatarRepository.save(corpus);

        try {
            markingCorpusRepository.save(new MarkingCorpus(
                    IdGenerator.newId(), orgId, subjectName, savedAvatar.getId(), Instant.now()));
            log.info("[MarkingCorpus] created marking brain org={} subject={} avatar={}",
                    orgId, subjectName, savedAvatar.getId());
            return savedAvatar.getId();
        } catch (DataIntegrityViolationException race) {
            // A concurrent first-upload for the same (org, subject) won the unique
            // key. Discard our orphan avatar and use the winner's mapping.
            log.info("[MarkingCorpus] lost create race org={} subject={} — using existing",
                    orgId, subjectName);
            try {
                avatarRepository.deleteById(savedAvatar.getId());
            } catch (Exception cleanup) {
                log.warn("[MarkingCorpus] orphan avatar cleanup failed avatar={}: {}",
                        savedAvatar.getId(), cleanup.getMessage());
            }
            return markingCorpusRepository.findByOrgIdAndSubject(orgId, subjectName)
                    .map(MarkingCorpus::avatarId)
                    .orElseThrow(() -> new BusinessException(
                            "Marking corpus race unresolved", 500));
        }
    }

    /** Read-only lookup for the grounding path — never creates. */
    public Optional<String> findAvatarId(String orgId, Subject subject) {
        if (orgId == null || orgId.isBlank()) return Optional.empty();
        return markingCorpusRepository.findByOrgIdAndSubject(orgId, subject.name())
                .map(MarkingCorpus::avatarId);
    }

    /** Ingest convenience: resolve (orgId, subject) from a classId, then create. */
    public String resolveOrCreateForClass(String classId) {
        String orgId = orgClassRepository.findOrganizationIdByClassId(classId)
                .orElseThrow(() -> new BusinessException("Class not found", 404));
        Subject subject = subjectForClass(classId);
        return resolveOrCreate(orgId, subject);
    }

    /** Grounding convenience: resolve the marking brain for a class, if it exists. */
    public Optional<String> findAvatarIdForClass(String classId) {
        Optional<String> orgId = orgClassRepository.findOrganizationIdByClassId(classId);
        if (orgId.isEmpty()) return Optional.empty();
        return findAvatarId(orgId.get(), subjectForClass(classId));
    }

    private Subject subjectForClass(String classId) {
        return normalizeSubject(
                orgClassRepository.findSubjectByClassId(classId).orElse(null));
    }

    /**
     * Normalises a class's free-text subject (e.g. "Maths", "P5 Math", "science")
     * into the {@code Subject} enum used to key the marking corpus. Falls back to
     * {@code GENERAL} so an unrecognised subject still gets one shared brain per
     * org rather than throwing.
     */
    Subject normalizeSubject(String raw) {
        if (raw == null || raw.isBlank()) return Subject.GENERAL;
        String stripped = raw.trim().toUpperCase().replaceAll("[^A-Z]", "");
        for (Subject s : Subject.values()) {
            if (s.name().equals(stripped)) return s;
        }
        String lower = raw.trim().toLowerCase();
        for (Subject s : Subject.values()) {
            if (s.label().toLowerCase().equals(lower)) return s;
        }
        // Heuristic substring match for the common free-text variants.
        if (lower.contains("math")) return Subject.MATHS;
        if (lower.contains("science")) return Subject.SCIENCE;
        if (lower.contains("english")) return Subject.ENGLISH;
        if (lower.contains("history")) return Subject.HISTORY;
        if (lower.contains("geograph")) return Subject.GEOGRAPHY;
        if (lower.contains("literat")) return Subject.LITERATURE;
        if (lower.contains("cod") || lower.contains("program")) return Subject.CODING;
        return Subject.GENERAL;
    }
}
