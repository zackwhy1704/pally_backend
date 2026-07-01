package com.pally.domain.marking;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.KnowledgeService;
import com.pally.domain.knowledge.usecase.UploadResult;
import com.pally.shared.util.InMemoryMultipartFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates a teacher marking-reference upload: it (1) stores the raw
 * artifact + extracted text as the {@link MarkingReference} record of truth
 * (unchanged), then (2) routes the SAME files into the centre's hidden
 * (orgId, subject) MARKING_CORPUS avatar through the existing knowledge
 * upload+extract+compile harness, so the marking standard is compiled,
 * merged-into-existing, deduped, conflict-checked and decayed — exactly like
 * the student notes-wiki — instead of piling up as flat text.
 *
 * <p>The corpus ingest is best-effort: the teacher's artifact is always saved,
 * and a compile hiccup never fails their upload (the compiled brain state is
 * surfaced separately so a failure is visible, not silent).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarkingIngestService {

    private final MarkingReferenceService markingReferenceService;
    private final MarkingCorpusService markingCorpusService;
    private final KnowledgeService knowledgeService;
    private final AvatarRepository avatarRepository;

    /**
     * Save the raw reference (record of truth) AND compile it into the marking
     * brain. Returns the saved reference; corpus ingest failures are logged, not
     * thrown.
     */
    public MarkingReference createAndIngest(String classId, MarkingReferenceKind kind,
                                            String title, String note,
                                            List<IncomingFile> files) {
        MarkingReference saved =
                markingReferenceService.create(classId, kind, title, note, files);
        try {
            ingestIntoCorpus(classId, files);
        } catch (Exception e) {
            // Best-effort: the artifact is stored; the brain can be recompiled.
            log.warn("[MarkingIngest] corpus ingest failed class={} ref={}: {}",
                    classId, saved.getId(), e.getMessage());
        }
        return saved;
    }

    private void ingestIntoCorpus(String classId, List<IncomingFile> files) {
        String corpusAvatarId = markingCorpusService.resolveOrCreateForClass(classId);
        String ownerUserId = avatarRepository.findById(corpusAvatarId)
                .map(Avatar::getUserId)
                .orElseThrow(() -> new IllegalStateException(
                        "Marking-corpus avatar has no owner: " + corpusAvatarId));

        int uploaded = 0;
        for (IncomingFile f : files) {
            InMemoryMultipartFile mpf = new InMemoryMultipartFile(
                    f.name(), f.name(), f.contentType(), f.bytes());
            // skipRelevance=true: marking materials are always in-scope for the
            // marking brain; they must not be filtered by the notes relevance gate.
            UploadResult result = knowledgeService.uploadFile(
                    ownerUserId, corpusAvatarId, mpf, true);
            if (result instanceof UploadResult.Failure fail) {
                log.warn("[MarkingIngest] file '{}' rejected for corpus={}: {}",
                        f.name(), corpusAvatarId, fail.message());
            } else {
                uploaded++;
            }
        }

        if (uploaded > 0) {
            // Compile the newly-uploaded marking materials into marking-wiki pages
            // (incremental — already-compiled files are skipped by the harness).
            knowledgeService.compileWiki(ownerUserId, corpusAvatarId);
            log.info("[MarkingIngest] ingested {} file(s) into marking corpus={} (class={})",
                    uploaded, corpusAvatarId, classId);
        }
    }
}
