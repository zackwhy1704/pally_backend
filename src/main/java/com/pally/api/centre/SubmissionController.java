package com.pally.api.centre;

import com.pally.domain.centre.CentreAccessService;
import com.pally.domain.centre.OrgClassRepository;
import com.pally.domain.homework.HomeworkSubmission;
import com.pally.domain.homework.HomeworkSubmissionStatus;
import com.pally.domain.homework.IncomingFile;
import com.pally.domain.homework.LoadedArtifact;
import com.pally.domain.homework.SubmissionFile;
import com.pally.domain.homework.HomeworkSubmissionService;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
 * Teacher-facing homework submission endpoints (centre/staff-gated). Mirrors the
 * Ren / MOE pattern: receive work → generate an AI first-pass draft → edit →
 * release. Nothing here releases anything to a student except {@code /release}.
 */
@RestController
@RequestMapping("/api/v1/centre/organizations/{orgId}/classes/{classId}/submissions")
@RequiredArgsConstructor
@Slf4j
public class SubmissionController {

    /** Per-file upload cap (matches the brain-upload limit). */
    private static final long MAX_FILE_BYTES = 25L * 1024 * 1024;
    private static final int MAX_FILES = 10;

    private final CentreAccessService accessService;
    private final OrgClassRepository orgClassRepository;
    private final HomeworkSubmissionService submissionService;

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> upload(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @RequestParam("files") MultipartFile[] files,
            @RequestParam("title") String title,
            @RequestParam(value = "studentId", required = false) String studentId,
            @RequestParam(value = "subject", required = false) String subject) {
        accessService.ensureStaff(userId, orgId);
        requireClass(orgId, classId);

        List<IncomingFile> incoming = readFiles(files);
        HomeworkSubmission saved = submissionService.createSubmission(
                classId, studentId, title, subject, incoming);
        return ResponseEntity.status(201).body(ApiResponse.created(toStaffDto(saved)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @RequestParam(value = "status", required = false) String status) {
        accessService.ensureStaff(userId, orgId);
        requireClass(orgId, classId);

        HomeworkSubmissionStatus filter = parseStatus(status);
        List<Map<String, Object>> out = submissionService.listForClass(classId, filter)
                .stream().map(this::toStaffDto).toList();
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    @GetMapping("/{submissionId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> detail(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String submissionId) {
        accessService.ensureStaff(userId, orgId);
        requireClass(orgId, classId);
        return ResponseEntity.ok(ApiResponse.success(toStaffDto(requireInClass(submissionId, classId))));
    }

    /** Stream a stored artifact for the teacher to view. */
    @GetMapping("/{submissionId}/files/{index}")
    public ResponseEntity<byte[]> file(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String submissionId,
            @PathVariable int index) {
        accessService.ensureStaff(userId, orgId);
        requireClass(orgId, classId);
        requireInClass(submissionId, classId);
        return streamArtifact(submissionService.loadArtifact(submissionId, index));
    }

    @PostMapping("/{submissionId}/ai-draft")
    public ResponseEntity<ApiResponse<Map<String, Object>>> aiDraft(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String submissionId) {
        accessService.ensureStaff(userId, orgId);
        requireClass(orgId, classId);
        requireInClass(submissionId, classId);
        return ResponseEntity.ok(ApiResponse.success(
                toStaffDto(submissionService.generateAiDraft(submissionId))));
    }

    @PatchMapping("/{submissionId}/review")
    public ResponseEntity<ApiResponse<Map<String, Object>>> review(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String submissionId,
            @RequestBody Map<String, Object> body) {
        accessService.ensureStaff(userId, orgId);
        requireClass(orgId, classId);
        requireInClass(submissionId, classId);
        return ResponseEntity.ok(ApiResponse.success(toStaffDto(
                submissionService.saveTeacherReview(
                        submissionId, str(body.get("feedback")), str(body.get("grade"))))));
    }

    @PostMapping("/{submissionId}/release")
    public ResponseEntity<ApiResponse<Map<String, Object>>> release(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String submissionId,
            @RequestBody(required = false) Map<String, Object> body) {
        accessService.ensureStaff(userId, orgId);
        requireClass(orgId, classId);
        requireInClass(submissionId, classId);
        // Allow a final inline edit on the same call (review + release in one tap).
        if (body != null && (body.containsKey("feedback") || body.containsKey("grade"))) {
            submissionService.saveTeacherReview(
                    submissionId, str(body.get("feedback")), str(body.get("grade")));
        }
        return ResponseEntity.ok(ApiResponse.success(
                toStaffDto(submissionService.release(submissionId))));
    }

    @PostMapping("/{submissionId}/return")
    public ResponseEntity<ApiResponse<Map<String, Object>>> returnForRedo(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String submissionId,
            @RequestBody(required = false) Map<String, Object> body) {
        accessService.ensureStaff(userId, orgId);
        requireClass(orgId, classId);
        requireInClass(submissionId, classId);
        String note = body == null ? null : str(body.get("note"));
        return ResponseEntity.ok(ApiResponse.success(
                toStaffDto(submissionService.returnForRedo(submissionId, note))));
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

    /** Loads the submission and verifies it belongs to this class (cross-class IDOR guard). */
    private HomeworkSubmission requireInClass(String submissionId, String classId) {
        HomeworkSubmission s = submissionService.get(submissionId);
        if (!classId.equals(s.getClassId())) {
            throw new BusinessException("Submission not found", 404);
        }
        return s;
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

    private Map<String, Object> toStaffDto(HomeworkSubmission s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("classId", s.getClassId());
        m.put("studentId", s.getStudentId());
        m.put("title", s.getTitle());
        m.put("subject", s.getSubject());
        m.put("status", s.getStatus().name());
        m.put("files", fileDtos(s.getFiles()));
        m.put("extractedText", s.getExtractedText());
        m.put("aiDraftFeedbackJson", s.getAiDraftFeedbackJson());
        m.put("aiDraftedAt", s.getAiDraftedAt() != null ? s.getAiDraftedAt().toString() : null);
        m.put("teacherFeedback", s.getTeacherFeedback());
        m.put("teacherGrade", s.getTeacherGrade());
        m.put("releasedAt", s.getReleasedAt() != null ? s.getReleasedAt().toString() : null);
        m.put("createdAt", s.getCreatedAt().toString());
        m.put("updatedAt", s.getUpdatedAt().toString());
        return m;
    }

    private List<Map<String, Object>> fileDtos(List<SubmissionFile> files) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            SubmissionFile f = files.get(i);
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("index", i);
            fm.put("name", f.name());
            fm.put("contentType", f.contentType());
            fm.put("size", f.size());
            out.add(fm);
        }
        return out;
    }

    private static HomeworkSubmissionStatus parseStatus(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return HomeworkSubmissionStatus.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Unknown status: " + s, 400);
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
