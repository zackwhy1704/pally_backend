package com.pally.domain.marking;

import com.pally.infrastructure.config.AiTaskExecutorConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * The FEED side of the marking feedback loop: folds captured teacher corrections
 * back into the marking-wiki so the next AI draft is grounded in "how THIS teacher
 * actually corrected me". The READ is already wired — {@code
 * HomeworkSubmissionService.loadMarkingContext} grounds the draft on the
 * marking-wiki — so this only writes.
 *
 * <p><b>Debounced</b> (the cost lesson from the weakness rebuild): corrections are
 * captured immediately (cheap), but the expensive marking-wiki recompile fires only
 * once a class accumulates {@code recompile-threshold} uncompiled corrections — not
 * per correction.
 *
 * <p><b>Damper</b>: unlike the student weakness loop (self-correcting — a right
 * answer clears a weakness), marking corrections have no natural correction, so
 * they could drift. Two mitigations here: (1) only the most-RECENT
 * {@code max-per-compile} corrections are fed, framed as "prefer the recent", and
 * (2) they go through the marking compile harness, which merges/dedups/DECAYS
 * (see {@code MarkingCorpusService}). Teacher removal (Part 4) is the manual damper.
 */
@Service
@Slf4j
public class MarkingCorrectionCompiler {

    private final MarkingCorrectionRepository repository;
    private final MarkingIngestService markingIngestService;

    /// Recompile only once this many uncompiled corrections have accrued (debounce).
    private final int recompileThreshold;
    /// Cap corrections fed into one recompile so a backlog can't bloat the prompt
    /// or let stale corrections dominate the recent signal.
    private final int maxPerCompile;

    public MarkingCorrectionCompiler(
            MarkingCorrectionRepository repository,
            MarkingIngestService markingIngestService,
            @Value("${marking.correction.recompile-threshold:5}") int recompileThreshold,
            @Value("${marking.correction.max-per-compile:20}") int maxPerCompile) {
        this.repository = repository;
        this.markingIngestService = markingIngestService;
        this.recompileThreshold = recompileThreshold;
        this.maxPerCompile = maxPerCompile;
    }

    /** Async, best-effort reaction to a captured correction. Never throws out. */
    @Async(AiTaskExecutorConfig.AI_TASK_EXECUTOR)
    @EventListener
    public void onCorrectionCaptured(MarkingCorrectionCapturedEvent event) {
        try {
            recompileClassIfDue(event.classId());
        } catch (Exception e) {
            log.warn("[Marking] correction recompile failed (non-fatal) class={}: {}",
                    event.classId(), e.toString());
        }
    }

    /**
     * Debounced recompile. If the class has accrued >= threshold uncompiled
     * corrections, fold the most recent ones into the marking-wiki and mark the
     * batch compiled. Returns the number of corrections consumed (0 = below
     * threshold / none). NOT best-effort itself — the async wrapper handles that —
     * so a compile failure leaves the corrections UNcompiled to retry next time
     * (markCompiled runs only after a successful ingest).
     */
    public int recompileClassIfDue(String classId) {
        List<MarkingCorrection> uncompiled = repository.findUncompiledByClassId(classId);
        if (uncompiled.size() < recompileThreshold) {
            return 0; // debounce — don't recompile on every correction
        }

        // Recency: newest first, capped. Older-than-cap corrections are still
        // consumed (marked compiled) but not fed — they're stale and the harness
        // decays anyway; leaving them uncompiled would re-trigger forever.
        List<MarkingCorrection> feed = uncompiled.stream()
                .sorted(Comparator.comparing(MarkingCorrection::capturedAt).reversed())
                .limit(maxPerCompile)
                .toList();

        String text = render(feed);
        IncomingFile source = new IncomingFile(
                "teacher-marking-corrections.txt", "text/plain",
                text.getBytes(StandardCharsets.UTF_8));

        // Reuse the marking compile harness (merge/dedup/conflict/decay). May throw
        // → the batch is NOT marked compiled below, so it retries next trigger.
        markingIngestService.ingestFiles(classId, List.of(source));

        repository.markCompiled(uncompiled.stream().map(MarkingCorrection::id).toList(), Instant.now());
        log.info("[Marking] recompiled marking-wiki class={} corrections consumed={} fed={}",
                classId, uncompiled.size(), feed.size());
        return uncompiled.size();
    }

    /** Render corrections as a marking-standard TEXT source, newest first. */
    private String render(List<MarkingCorrection> corrections) {
        StringBuilder sb = new StringBuilder();
        sb.append("# TEACHER MARKING CORRECTIONS\n")
          .append("How THIS teacher actually marks — cases where the AI's draft was wrong and the ")
          .append("teacher corrected it. Weight these heavily and PREFER THE MOST RECENT (listed first): ")
          .append("they reflect the teacher's current standard. Apply the same judgement to similar work.\n\n");
        for (MarkingCorrection c : corrections) {
            sb.append("## ")
              .append(c.subject() == null || c.subject().isBlank() ? "Correction" : c.subject())
              .append(" (").append(c.capturedAt()).append(")\n");
            if (notBlank(c.aiSuggestedGrade())) {
                sb.append("- Grade: the AI suggested ").append(c.aiSuggestedGrade())
                  .append("; the teacher awarded ").append(orDash(c.teacherGrade())).append(".\n");
            }
            if (notBlank(c.aiFeedback())) {
                sb.append("- Feedback: the AI wrote \"").append(c.aiFeedback())
                  .append("\"; the teacher corrected it to \"").append(orDash(c.teacherFeedback()))
                  .append("\".\n");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }
}
