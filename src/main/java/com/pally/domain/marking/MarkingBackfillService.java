package com.pally.domain.marking;

import com.pally.domain.centre.OrgClassRepository;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.domain.knowledge.port.StoragePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One-time (idempotent) backfill: routes EXISTING raw {@link MarkingReference}
 * artifacts — uploaded before the marking-wiki harness — into their class's
 * (orgId, subject) marking corpus so the compiled marking standard includes the
 * teacher's historical materials. Raw references remain the store of record.
 *
 * <p>Idempotent by a stable per-reference file name ({@code mref-<refId>-<name>}):
 * a reference already present in the corpus is skipped, so re-running is safe.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarkingBackfillService {

    private final MarkingReferenceRepository markingReferenceRepository;
    private final MarkingCorpusService markingCorpusService;
    private final MarkingIngestService markingIngestService;
    private final KnowledgeRepository knowledgeRepository;
    private final StoragePort storagePort;
    private final OrgClassRepository orgClassRepository;

    /** Backfill every class in an org. Returns the number of files ingested. */
    public int backfillOrg(String orgId) {
        int total = 0;
        for (String classId : orgClassRepository.findClassIdsByOrgId(orgId)) {
            total += backfillClass(classId);
        }
        log.info("[MarkingBackfill] org={} backfilled {} file(s)", orgId, total);
        return total;
    }

    /** Backfill one class's raw references into its marking corpus. */
    public int backfillClass(String classId) {
        List<MarkingReference> refs = markingReferenceRepository.findByClassId(classId);
        if (refs.isEmpty()) return 0;

        String corpusAvatarId = markingCorpusService.resolveOrCreateForClass(classId);
        Set<String> existingNames = new HashSet<>();
        for (KnowledgeFile kf : knowledgeRepository.findByAvatarId(corpusAvatarId)) {
            existingNames.add(kf.getFileName());
        }

        List<IncomingFile> toIngest = new ArrayList<>();
        for (MarkingReference ref : refs) {
            for (MarkingReferenceFile f : ref.getFiles()) {
                String name = backfillName(ref.getId(), f.name());
                if (existingNames.contains(name)) {
                    continue; // already backfilled — idempotent skip
                }
                byte[] bytes;
                try {
                    bytes = storagePort.download(f.key());
                } catch (Exception e) {
                    log.warn("[MarkingBackfill] could not download ref={} key={}: {}",
                            ref.getId(), f.key(), e.getMessage());
                    continue;
                }
                toIngest.add(new IncomingFile(name, f.contentType(), bytes));
            }
        }

        if (toIngest.isEmpty()) return 0;
        markingIngestService.ingestFiles(classId, toIngest);
        log.info("[MarkingBackfill] class={} ingested {} file(s) into corpus={}",
                classId, toIngest.size(), corpusAvatarId);
        return toIngest.size();
    }

    private static String backfillName(String refId, String fileName) {
        return "mref-" + refId + "-" + (fileName == null ? "file" : fileName);
    }
}
