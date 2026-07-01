package com.pally.domain.homework;

import com.pally.domain.centre.OrgClassRepository;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.port.StoragePort;
import com.pally.infrastructure.push.FcmService;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Homework submissions, teacher-in-the-loop. A student (or a teacher on their
 * behalf) uploads the student's work; the teacher generates an AI first-pass
 * feedback draft grounded in the class brain, edits it, and releases it.
 *
 * <p>The teacher-in-the-loop guarantee is structural: AI output is stored only
 * in {@code aiDraftFeedbackJson} and never advances a submission past
 * {@link HomeworkSubmissionStatus#AI_DRAFTED}; nothing reaches the student until
 * {@link #release} (which requires non-blank teacher feedback).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HomeworkSubmissionService {

    /** Cap the brain context we feed the model so a huge class brain can't blow the prompt. */
    private static final int MAX_BRAIN_CHARS = 12_000;
    /** Cap the work text similarly (a long multi-page extraction is still bounded). */
    private static final int MAX_WORK_CHARS = 12_000;
    /** Cap the teacher's marking-standard grounding so it can't crowd out the rest. */
    private static final int MAX_MARKING_CHARS = 8_000;

    private final HomeworkSubmissionRepository submissionRepository;
    private final StoragePort storagePort;
    private final DocumentTextExtractionPort textExtractor;
    private final HomeworkFeedbackGenerator feedbackGenerator;
    private final OrgClassRepository orgClassRepository;
    private final WikiRepository wikiRepository;
    private final FcmService fcmService;
    private final com.pally.domain.marking.MarkingCorpusService markingCorpusService;

    // ── Create (student submits, or teacher uploads on a student's behalf) ──────

    /**
     * Stores each file, extracts text best-effort, and persists a SUBMITTED row.
     * An empty/unreadable extraction is allowed — the artifact is still viewable.
     */
    public HomeworkSubmission createSubmission(String classId, String studentId, String title,
                                               String subject, List<IncomingFile> files) {
        if (classId == null || classId.isBlank()) {
            throw new BusinessException("classId is required", 400);
        }
        if (title == null || title.isBlank()) {
            throw new BusinessException("A title is required", 400);
        }
        if (files == null || files.isEmpty()) {
            throw new BusinessException("At least one file is required", 400);
        }

        List<SubmissionFile> stored = new ArrayList<>();
        StringBuilder extracted = new StringBuilder();
        for (IncomingFile f : files) {
            String key = buildKey(classId, studentId, f.name());
            storagePort.upload(key, f.bytes(), f.contentType());
            stored.add(new SubmissionFile(key, f.name(), f.contentType(),
                    f.bytes() != null ? f.bytes().length : 0));

            String text = textExtractor.extract(f.bytes(), f.contentType());
            if (text != null && !text.isBlank()) {
                if (extracted.length() > 0) extracted.append("\n\n");
                extracted.append(text.trim());
            }
        }

        HomeworkSubmission submission = HomeworkSubmission.create(
                classId, studentId, title.trim(),
                subject == null || subject.isBlank() ? null : subject.trim(),
                stored, clamp(extracted.toString(), MAX_WORK_CHARS * 4));
        HomeworkSubmission saved = submissionRepository.save(submission);
        log.info("[Homework] created submission={} class={} student={} files={}",
                saved.getId(), classId, studentId, stored.size());
        return saved;
    }

    // ── AI first-pass draft (teacher-triggered, never released) ─────────────────

    /**
     * Generates an AI feedback draft grounded in the class brain. NEVER releases.
     * On AI failure the submission is left untouched and a 503 is thrown so the
     * teacher can retry or mark manually.
     */
    public HomeworkSubmission generateAiDraft(String submissionId) {
        HomeworkSubmission submission = require(submissionId);
        if (submission.isReleased()) {
            throw new BusinessException("This submission has already been released", 409);
        }

        String brain = clamp(loadBrainContext(submission.getClassId()), MAX_BRAIN_CHARS);
        // The teacher's marking standard is now a COMPILED marking-wiki (see
        // MarkingCorpusService), not a flat concatenation of raw references.
        String marking = clamp(loadMarkingContext(submission.getClassId()), MAX_MARKING_CHARS);
        // A marking task: the marking standard leads, class material is background.
        String grounding = combineGrounding(marking, brain);
        String work = clamp(
                submission.getExtractedText() == null ? "" : submission.getExtractedText(),
                MAX_WORK_CHARS);

        String draftJson;
        try {
            draftJson = feedbackGenerator.generateDraftJson(
                    grounding, work, submission.getTitle(), submission.getSubject());
        } catch (Exception e) {
            log.warn("[Homework] AI draft failed for submission={}: {}", submissionId, e.toString());
            throw new BusinessException(
                    "Couldn't generate an AI draft right now — you can still mark this manually.", 503);
        }

        submission.applyAiDraft(draftJson);
        return submissionRepository.save(submission);
    }

    // ── Teacher review + release ────────────────────────────────────────────────

    /** Save the teacher's in-progress feedback/grade (status TEACHER_REVIEWING). */
    public HomeworkSubmission saveTeacherReview(String submissionId, String feedback, String grade) {
        HomeworkSubmission submission = require(submissionId);
        submission.applyTeacherReview(
                feedback == null ? null : feedback.trim(),
                grade == null || grade.isBlank() ? null : grade.trim());
        return submissionRepository.save(submission);
    }

    /**
     * Release the teacher's feedback to the student. Requires non-blank feedback
     * (the human-sign-off invariant). Best-effort push notify, no payment content.
     */
    public HomeworkSubmission release(String submissionId) {
        HomeworkSubmission submission = require(submissionId);
        try {
            submission.release();
        } catch (IllegalStateException e) {
            throw new BusinessException("Add your feedback before releasing it to the student.", 400);
        }
        HomeworkSubmission saved = submissionRepository.save(submission);
        notifyReleased(saved);
        return saved;
    }

    /** Send back for redo. */
    public HomeworkSubmission returnForRedo(String submissionId, String note) {
        HomeworkSubmission submission = require(submissionId);
        submission.returnForRedo(note == null ? null : note.trim());
        return submissionRepository.save(submission);
    }

    // ── Reads ───────────────────────────────────────────────────────────────────

    public List<HomeworkSubmission> listForClass(String classId, HomeworkSubmissionStatus status) {
        return status == null
                ? submissionRepository.findByClassId(classId)
                : submissionRepository.findByClassIdAndStatus(classId, status);
    }

    public List<HomeworkSubmission> listForStudent(String studentId, String classId) {
        return submissionRepository.findByStudentIdAndClassId(studentId, classId);
    }

    public HomeworkSubmission get(String submissionId) {
        return require(submissionId);
    }

    /**
     * Loads a stored artifact for streaming. Access control is the caller's
     * responsibility (staff guard or student-ownership check) — this only
     * resolves the bytes.
     */
    public LoadedArtifact loadArtifact(String submissionId, int fileIndex) {
        HomeworkSubmission submission = require(submissionId);
        List<SubmissionFile> files = submission.getFiles();
        if (fileIndex < 0 || fileIndex >= files.size()) {
            throw new BusinessException("File not found", 404);
        }
        SubmissionFile f = files.get(fileIndex);
        byte[] bytes = storagePort.download(f.key());
        return new LoadedArtifact(bytes, f.contentType(), f.name());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private HomeworkSubmission require(String submissionId) {
        return submissionRepository.findById(submissionId)
                .orElseThrow(() -> new BusinessException("Submission not found", 404));
    }

    /** Concatenate the class's active brain pages (human-verified text preferred). */
    private String loadBrainContext(String classId) {
        String corpusAvatarId = orgClassRepository.findCorpusAvatarIdByClassId(classId).orElse(null);
        if (corpusAvatarId == null) {
            return "";
        }
        List<WikiPage> pages = wikiRepository.findActiveByAvatarId(corpusAvatarId);
        StringBuilder sb = new StringBuilder();
        for (WikiPage p : pages) {
            String body = p.getHumanCorrection() != null && !p.getHumanCorrection().isBlank()
                    ? p.getHumanCorrection() : p.getContent();
            if (body == null || body.isBlank()) continue;
            sb.append("## ").append(p.getTitle()).append('\n').append(body.trim()).append("\n\n");
            if (sb.length() > MAX_BRAIN_CHARS) break;
        }
        return sb.toString();
    }

    /** Concatenate the centre's compiled MARKING-STANDARD pages for this class's
     * (orgId, subject). This is the authority on HOW to mark; empty when no
     * marking materials have been compiled yet. */
    private String loadMarkingContext(String classId) {
        String markingAvatarId = markingCorpusService.findAvatarIdForClass(classId).orElse(null);
        if (markingAvatarId == null) {
            return "";
        }
        List<WikiPage> pages = wikiRepository.findActiveByAvatarId(markingAvatarId);
        StringBuilder sb = new StringBuilder();
        for (WikiPage p : pages) {
            String body = p.getHumanCorrection() != null && !p.getHumanCorrection().isBlank()
                    ? p.getHumanCorrection() : p.getContent();
            if (body == null || body.isBlank()) continue;
            sb.append("## ").append(p.getTitle()).append('\n').append(body.trim()).append("\n\n");
            if (sb.length() > MAX_MARKING_CHARS) break;
        }
        return sb.toString();
    }

    /**
     * Fold the compiled marking standard and the class brain into the grounding
     * the model marks against. For a MARKING task the standard leads (the
     * authority on HOW to mark) and the class material follows as background.
     * Purely additive: an empty section is omitted.
     */
    private static String combineGrounding(String marking, String brain) {
        StringBuilder sb = new StringBuilder();
        if (marking != null && !marking.isBlank()) {
            sb.append("=== TEACHER'S MARKING STANDARD (mark against this FIRST) ===\n")
              .append(marking.strip()).append("\n\n");
        }
        if (brain != null && !brain.isBlank()) {
            sb.append("=== CLASS MATERIAL (background context) ===\n").append(brain.strip());
        }
        return sb.toString().strip();
    }

    private void notifyReleased(HomeworkSubmission submission) {
        if (submission.getStudentId() == null || !fcmService.isConfigured()) {
            return;
        }
        // Value-framed, in-app destination only — no payment, no external URL.
        fcmService.sendToUser(
                submission.getStudentId(),
                "Your homework was marked ✅",
                "Your teacher left feedback on “" + submission.getTitle() + "”. Open Apalchi to see it.",
                Map.of());
    }

    private static String buildKey(String classId, String studentId, String fileName) {
        String safe = (fileName == null ? "file" : fileName).replaceAll("[^a-zA-Z0-9._-]", "_");
        return "submissions/" + classId + "/" + (studentId == null ? "unassigned" : studentId)
                + "/" + System.currentTimeMillis() + "_" + safe;
    }

    private static String clamp(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
