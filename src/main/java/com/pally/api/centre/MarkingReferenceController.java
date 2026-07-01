package com.pally.api.centre;

import com.pally.domain.centre.CentreAccessService;
import com.pally.domain.centre.OrgClassRepository;
import com.pally.domain.marking.IncomingFile;
import com.pally.domain.marking.LoadedArtifact;
import com.pally.domain.marking.MarkingReference;
import com.pally.domain.marking.MarkingReferenceFile;
import com.pally.domain.marking.MarkingIngestService;
import com.pally.domain.marking.MarkingReferenceKind;
import com.pally.domain.marking.MarkingReferenceService;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Teacher-facing marking-reference endpoints (centre/staff-gated). A teacher
 * uploads past marked papers, rubrics, and guidelines to train their marking
 * assistant; these ground the AI homework-feedback draft in that teacher's own
 * standard. Read-mostly admin of grounding material — nothing here touches a
 * student.
 */
@RestController
@RequestMapping("/api/v1/centre/organizations/{orgId}/classes/{classId}/marking-references")
@RequiredArgsConstructor
@Slf4j
public class MarkingReferenceController {

    /** Per-file upload cap (matches the homework/brain-upload limit). */
    private static final long MAX_FILE_BYTES = 25L * 1024 * 1024;
    private static final int MAX_FILES = 10;

    private final CentreAccessService accessService;
    private final OrgClassRepository orgClassRepository;
    private final MarkingReferenceService markingService;
    private final MarkingIngestService markingIngestService;

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> upload(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @RequestParam("files") MultipartFile[] files,
            @RequestParam("kind") String kind,
            @RequestParam("title") String title,
            @RequestParam(value = "note", required = false) String note) {
        accessService.ensureStaff(userId, orgId);
        requireClass(orgId, classId);

        List<IncomingFile> incoming = readFiles(files);
        // Stores the raw artifact AND compiles it into the (orgId, subject)
        // marking brain via the wiki harness (see MarkingIngestService).
        MarkingReference saved = markingIngestService.createAndIngest(
                classId, parseKind(kind), title, note, incoming);
        return ResponseEntity.status(201).body(ApiResponse.created(toDto(saved)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId) {
        accessService.ensureStaff(userId, orgId);
        requireClass(orgId, classId);

        List<Map<String, Object>> out = new ArrayList<>();
        for (MarkingReference r : markingService.listForClass(classId)) {
            out.add(toDto(r));
        }
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    @GetMapping("/{referenceId}/files/{index}")
    public ResponseEntity<byte[]> file(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String referenceId,
            @PathVariable int index) {
        accessService.ensureStaff(userId, orgId);
        requireClass(orgId, classId);
        requireInClass(referenceId, classId);

        LoadedArtifact artifact = markingService.loadArtifact(referenceId, index);
        return streamArtifact(artifact);
    }

    @DeleteMapping("/{referenceId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> delete(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String referenceId) {
        accessService.ensureStaff(userId, orgId);
        requireClass(orgId, classId);
        requireInClass(referenceId, classId);

        markingService.delete(referenceId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("deleted", true)));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private List<IncomingFile> readFiles(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw new BusinessException("At least one file is required", 400);
        }
        if (files.length > MAX_FILES) {
            throw new BusinessException("Too many files (max " + MAX_FILES + ")", 400);
        }
        List<IncomingFile> out = new ArrayList<>();
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) continue;
            if (f.getSize() > MAX_FILE_BYTES) {
                throw new BusinessException("File too large (max 25MB): " + f.getOriginalFilename(), 413);
            }
            try {
                out.add(new IncomingFile(
                        f.getOriginalFilename(),
                        f.getContentType() == null ? "application/octet-stream" : f.getContentType(),
                        f.getBytes()));
            } catch (IOException e) {
                throw new BusinessException("Could not read uploaded file", 400);
            }
        }
        if (out.isEmpty()) {
            throw new BusinessException("At least one non-empty file is required", 400);
        }
        return out;
    }

    private void requireClass(String orgId, String classId) {
        String owningOrg = orgClassRepository.findOrganizationIdByClassId(classId)
                .orElseThrow(() -> new BusinessException("Class not found", 404));
        if (!orgId.equals(owningOrg)) {
            throw new BusinessException("Class not in this organization", 403);
        }
    }

    /** Loads the reference and verifies it belongs to this class (cross-class IDOR guard). */
    private void requireInClass(String referenceId, String classId) {
        MarkingReference r = markingService.get(referenceId);
        if (!classId.equals(r.getClassId())) {
            throw new BusinessException("Marking reference not found", 404);
        }
    }

    private ResponseEntity<byte[]> streamArtifact(LoadedArtifact a) {
        MediaType type;
        try {
            type = MediaType.parseMediaType(a.contentType());
        } catch (Exception e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + (a.name() == null ? "file" : a.name()) + "\"")
                .body(a.bytes());
    }

    private Map<String, Object> toDto(MarkingReference r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("classId", r.getClassId());
        m.put("kind", r.getKind().name());
        m.put("title", r.getTitle());
        m.put("note", r.getNote());
        m.put("files", fileDtos(r.getFiles()));
        m.put("extractedChars", r.getExtractedChars());
        m.put("createdAt", r.getCreatedAt().toString());
        m.put("updatedAt", r.getUpdatedAt().toString());
        return m;
    }

    private List<Map<String, Object>> fileDtos(List<MarkingReferenceFile> files) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            MarkingReferenceFile f = files.get(i);
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("index", i);
            fm.put("name", f.name());
            fm.put("contentType", f.contentType());
            fm.put("size", f.size());
            out.add(fm);
        }
        return out;
    }

    private static MarkingReferenceKind parseKind(String s) {
        if (s == null || s.isBlank()) {
            throw new BusinessException("A reference kind is required", 400);
        }
        try {
            return MarkingReferenceKind.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Unknown kind: " + s, 400);
        }
    }
}
