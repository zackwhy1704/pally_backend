package com.pally.api.module;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.centre.CentreAccessService;
import com.pally.domain.homework.HomeworkSubmission;
import com.pally.domain.homework.HomeworkSubmissionStatus;
import com.pally.domain.homework.HomeworkSubmissionService;
import com.pally.domain.homework.IncomingFile;
import com.pally.domain.homework.LoadedArtifact;
import com.pally.domain.homework.SubmissionFile;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
 * Student-facing homework submission endpoints, scoped to an avatar (centre
 * class). A student uploads their OWN work and later reads the teacher's
 * RELEASED feedback. The AI draft and any pre-release teacher notes are
 * server-withheld — only the released {@code teacherFeedback}/{@code teacherGrade}
 * are ever exposed here, and only after the teacher hits Release.
 */
@RestController
@RequestMapping("/api/v1/avatars/{avatarId}/homework")
@RequiredArgsConstructor
@Slf4j
public class HomeworkStudentController {

    private static final long MAX_FILE_BYTES = 25L * 1024 * 1024;
    private static final int MAX_FILES = 10;

    private final AvatarRepository avatarRepository;
    private final CentreAccessService accessService;
    private final HomeworkSubmissionService submissionService;

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> submit(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @RequestParam("files") MultipartFile[] files,
            @RequestParam("title") String title,
            @RequestParam(value = "subject", required = false) String subject) {
        String classId = requireCentreAvatar(userId, avatarId).getClassId();
        List<IncomingFile> incoming = readFiles(files);
        HomeworkSubmission saved = submissionService.createSubmission(
                classId, userId, title, subject, incoming);
        return ResponseEntity.status(201).body(ApiResponse.created(toStudentDto(saved)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listMine(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId) {
        Avatar avatar = avatarRepository.findById(avatarId)
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));
        requireOwner(userId, avatar);
        String classId = avatar.getClassId();
        if (classId == null || classId.isBlank()) {
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }
        List<Map<String, Object>> out = submissionService.listForStudent(userId, classId)
                .stream().map(this::toStudentDto).toList();
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    @GetMapping("/{submissionId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> detail(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @PathVariable String submissionId) {
        requireOwner(userId, avatarRepository.findById(avatarId)
                .orElseThrow(() -> new AvatarNotFoundException(avatarId)));
        return ResponseEntity.ok(ApiResponse.success(
                toStudentDto(requireOwnSubmission(userId, submissionId))));
    }

    @GetMapping("/{submissionId}/files/{index}")
    public ResponseEntity<byte[]> file(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @PathVariable String submissionId,
            @PathVariable int index) {
        requireOwner(userId, avatarRepository.findById(avatarId)
                .orElseThrow(() -> new AvatarNotFoundException(avatarId)));
        requireOwnSubmission(userId, submissionId);
        LoadedArtifact a = submissionService.loadArtifact(submissionId, index);
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

    // ── Guards ──────────────────────────────────────────────────────────────

    private Avatar requireCentreAvatar(String userId, String avatarId) {
        Avatar avatar = avatarRepository.findById(avatarId)
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));
        requireOwner(userId, avatar);
        String classId = avatar.getClassId();
        if (classId == null || classId.isBlank()) {
            throw new BusinessException("Homework is only available for centre classes", 400);
        }
        if (!accessService.isActiveClassMember(userId, classId)) {
            throw new BusinessException("You are not an active member of this class", 403);
        }
        return avatar;
    }

    private void requireOwner(String userId, Avatar avatar) {
        if (!userId.equals(avatar.getUserId())) {
            throw new BusinessException("This avatar does not belong to you", 403);
        }
    }

    private HomeworkSubmission requireOwnSubmission(String userId, String submissionId) {
        HomeworkSubmission s = submissionService.get(submissionId);
        if (!userId.equals(s.getStudentId())) {
            throw new BusinessException("Submission not found", 404);
        }
        return s;
    }

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

    /**
     * Student view: NEVER exposes the AI draft or pre-release teacher notes.
     * {@code teacherFeedback}/{@code teacherGrade} appear only once RELEASED.
     */
    private Map<String, Object> toStudentDto(HomeworkSubmission s) {
        boolean released = s.getStatus() == HomeworkSubmissionStatus.RELEASED;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("classId", s.getClassId());
        m.put("title", s.getTitle());
        m.put("subject", s.getSubject());
        // Collapse internal review states to a single "in review" signal for students.
        m.put("status", released ? "RELEASED"
                : s.getStatus() == HomeworkSubmissionStatus.RETURNED ? "RETURNED" : "IN_REVIEW");
        m.put("files", fileDtos(s.getFiles()));
        m.put("teacherFeedback", released ? s.getTeacherFeedback() : null);
        m.put("teacherGrade", released ? s.getTeacherGrade() : null);
        m.put("releasedAt", released && s.getReleasedAt() != null ? s.getReleasedAt().toString() : null);
        m.put("createdAt", s.getCreatedAt().toString());
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
}
