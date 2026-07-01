package com.pally.domain.marking;

import com.pally.domain.homework.DocumentTextExtractionPort;
import com.pally.domain.knowledge.port.StoragePort;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * The teacher "marking assistant" — the BRAIN moat for teachers. A teacher
 * uploads past MARKED papers, rubrics, and guidelines; we store the originals
 * and extract their text so the AI homework-feedback draft is grounded in THAT
 * teacher's own marking standard instead of a generic syllabus.
 *
 * <p>This is the RAW artifact store of record. The grounding the AI marks
 * against is the COMPILED marking-wiki (see {@code MarkingIngestService} +
 * {@code MarkingCorpusService}), not this flat text — so a teacher's uploads
 * merge and refine instead of piling up as competing blobs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarkingReferenceService {

    /** Per-reference stored extraction cap (a multi-page paper is still bounded). */
    private static final int MAX_EXTRACT_CHARS = 40_000;

    private final MarkingReferenceRepository repository;
    private final StoragePort storagePort;
    private final DocumentTextExtractionPort textExtractor;

    // ── Create ──────────────────────────────────────────────────────────────

    /**
     * Stores each file, extracts text best-effort, and persists the reference.
     * An unreadable extraction is allowed — the artifact is still viewable and
     * still counts as guidance the teacher chose to add.
     */
    public MarkingReference create(String classId, MarkingReferenceKind kind, String title,
                                   String note, List<IncomingFile> files) {
        if (classId == null || classId.isBlank()) {
            throw new BusinessException("classId is required", 400);
        }
        if (kind == null) {
            throw new BusinessException("A reference kind is required", 400);
        }
        if (title == null || title.isBlank()) {
            throw new BusinessException("A title is required", 400);
        }
        if (files == null || files.isEmpty()) {
            throw new BusinessException("At least one file is required", 400);
        }

        List<MarkingReferenceFile> stored = new ArrayList<>();
        StringBuilder extracted = new StringBuilder();
        for (IncomingFile f : files) {
            String key = buildKey(classId, f.name());
            storagePort.upload(key, f.bytes(), f.contentType());
            stored.add(new MarkingReferenceFile(key, f.name(), f.contentType(),
                    f.bytes() != null ? f.bytes().length : 0));

            String text = textExtractor.extract(f.bytes(), f.contentType());
            if (text != null && !text.isBlank()) {
                if (extracted.length() > 0) extracted.append("\n\n");
                extracted.append(text.trim());
            }
        }

        MarkingReference ref = MarkingReference.create(
                classId, kind, title.trim(),
                note == null || note.isBlank() ? null : note.trim(),
                stored, clamp(extracted.toString(), MAX_EXTRACT_CHARS));
        MarkingReference saved = repository.save(ref);
        log.info("[Marking] created reference={} class={} kind={} files={} chars={}",
                saved.getId(), classId, kind, stored.size(), saved.getExtractedChars());
        return saved;
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    public List<MarkingReference> listForClass(String classId) {
        return repository.findByClassId(classId);
    }

    public MarkingReference get(String id) {
        return require(id);
    }

    /**
     * Loads a stored artifact for streaming. Access control is the caller's
     * responsibility (staff guard + class-ownership check) — this only resolves
     * the bytes.
     */
    public LoadedArtifact loadArtifact(String id, int fileIndex) {
        MarkingReference ref = require(id);
        List<MarkingReferenceFile> files = ref.getFiles();
        if (fileIndex < 0 || fileIndex >= files.size()) {
            throw new BusinessException("File not found", 404);
        }
        MarkingReferenceFile f = files.get(fileIndex);
        byte[] bytes = storagePort.download(f.key());
        return new LoadedArtifact(bytes, f.contentType(), f.name());
    }

    // ── Delete ──────────────────────────────────────────────────────────────

    /** Removes the reference and its stored artifacts (best-effort on storage). */
    public void delete(String id) {
        MarkingReference ref = require(id);
        for (MarkingReferenceFile f : ref.getFiles()) {
            try {
                storagePort.delete(f.key());
            } catch (Exception e) {
                // The DB row is the source of truth; an orphaned blob is harmless.
                log.warn("[Marking] failed to delete artifact key={}: {}", f.key(), e.toString());
            }
        }
        repository.deleteById(id);
        log.info("[Marking] deleted reference={} class={}", id, ref.getClassId());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private MarkingReference require(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException("Marking reference not found", 404));
    }

    private static String buildKey(String classId, String fileName) {
        String safe = (fileName == null ? "file" : fileName).replaceAll("[^a-zA-Z0-9._-]", "_");
        return "marking/" + classId + "/" + System.currentTimeMillis() + "_" + safe;
    }

    private static String clamp(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
