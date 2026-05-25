package com.pally.domain.chat;

import com.pally.domain.avatar.TeachingMode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Builds the Block 4 dynamic tail for the prompt caching system.
 * Block 4 has no cache_control — it changes per message based on Socratic state.
 */
@Component
public class SocraticPromptBuilder {

    private static final int MAX_BLOCK4_TOKENS = 120;

    /**
     * Builds Block 4 map for inclusion in the system blocks array.
     * Content varies based on: teaching mode, attempt count, hint tree, escape hatch flag.
     */
    public Map<String, Object> buildBlock4(
            TeachingMode mode,
            Optional<SocraticHintTree> hintTree,
            int attemptCount,
            boolean shouldEscape
    ) {
        String content = buildContent(mode, hintTree, attemptCount, shouldEscape);
        return Map.of("type", "text", "text", content);
    }

    private String buildContent(
            TeachingMode mode,
            Optional<SocraticHintTree> hintTree,
            int attemptCount,
            boolean shouldEscape
    ) {
        if (mode == TeachingMode.DIRECT) {
            return "The student has chosen direct answers. Give clear, complete explanations. Do not ask guiding questions — just explain the concept fully and directly.";
        }

        if (shouldEscape) {
            return "The student has tried hard and made " + attemptCount + " attempts. Now give the full, direct answer. Acknowledge their effort warmly, then explain the complete solution step by step.";
        }

        if (hintTree.isPresent() && attemptCount <= 3) {
            SocraticHintTree tree = hintTree.get();
            List<SocraticHintTree.HintStep> hints = tree.getHints();

            int stepIndex = Math.min(attemptCount, hints.size() - 1);
            if (!hints.isEmpty()) {
                SocraticHintTree.HintStep step = hints.get(stepIndex);
                return "Guide the student Socratically. Ask this guiding question to lead them toward the answer: \""
                        + step.guidingQuestion() + "\". Do NOT give the answer directly. If they are on the right track, encourage them and ask the next question.";
            }
        }

        // Default Socratic prompt (no hint tree match)
        return "Guide the student with a Socratic question. Ask one short question that helps them think through the problem themselves. Do not give the answer directly.";
    }

    /** Formats hint steps for logging/debugging. */
    public static String summariseHints(SocraticHintTree tree) {
        return tree.getHints().stream()
                .map(h -> "Step " + h.stepNumber() + ": " + h.guidingQuestion())
                .collect(Collectors.joining(" | "));
    }
}
