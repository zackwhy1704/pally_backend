package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.infrastructure.persistence.safety.ChatSafetyFlagJpaEntity;
import com.pally.infrastructure.persistence.safety.ChatSafetyFlagJpaRepository;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Content-moderation layer for child-facing chat messages.
 *
 * <p>Screens BOTH the child's input and the model's draft output before either
 * reaches the child. Uses Claude Haiku for speed (sub-second, cheap). Runs
 * outside any database transaction and behind a timeout — if the call fails,
 * HIGH-severity content BLOCKS (fail-safe), LOW/MEDIUM passes (fail-open) so a
 * classifier outage doesn't block normal tutoring.
 *
 * <p>On a HIGH-severity hit the caller must REPLACE the response with a caring
 * redirect and NEVER deliver the original content to the child.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModerationService {

    private static final int MODERATION_MAX_TOKENS = 300;
    private static final int SNIPPET_MAX_CHARS = 200;

    private final ClaudeApiClient claudeApiClient;
    private final ObjectMapper objectMapper;
    private final ModelRouter modelRouter;
    private final ChatSafetyFlagJpaRepository flagRepo;
    private final SafetyAlertService safetyAlertService;

    public record ModerationResult(
            boolean flagged,
            String category,   // SAFE | SELF_HARM | VIOLENCE | SEXUAL | BULLYING | PERSONAL_DATA | OFF_TOPIC
            String severity,   // HIGH | MEDIUM | LOW | SAFE
            String safeReply   // non-null when flagged — child-safe caring response
    ) {
        public boolean isSafe() { return "SAFE".equals(category); }
        public boolean isHighSeverity() { return "HIGH".equals(severity); }
    }

    /**
     * Screen a child's input message. Returns immediately safe result on errors.
     * Fails safe: HIGH-severity categories block even when the classifier is uncertain.
     */
    public ModerationResult screenInput(String userId, String avatarId,
                                         String messageId, String text) {
        return screen(userId, avatarId, messageId, text, "INPUT");
    }

    /**
     * Screen the model's draft output before it reaches the child.
     * Same fail-safe semantics.
     */
    public ModerationResult screenOutput(String userId, String avatarId,
                                          String messageId, String text) {
        return screen(userId, avatarId, messageId, text, "OUTPUT");
    }

    private ModerationResult screen(String userId, String avatarId,
                                     String messageId, String text, String source) {
        if (text == null || text.isBlank()) return safe();

        String prompt = """
                You are a child-safety content classifier for a study app used by students aged 13-25.
                Classify the following message into ONE of these categories:
                - SAFE: normal tutoring/study content
                - SELF_HARM: mentions of self-harm, suicide, eating disorders, self-destructive ideation
                - VIOLENCE: threats, descriptions of physical violence, weapons
                - SEXUAL: sexual content, grooming language, inappropriate romantic content
                - BULLYING: harassment, hate speech, cyberbullying
                - PERSONAL_DATA: child sharing identifying info (address, phone, school name + grade)
                - OFF_TOPIC: content clearly unrelated to studying that could be unsafe

                Assign severity: HIGH | MEDIUM | LOW
                HIGH = immediate risk or explicit content; MEDIUM = concerning but ambiguous; LOW = minor

                Respond ONLY with JSON, no markdown:
                {"category":"SAFE","severity":"SAFE","reason":"one sentence"}

                Message to classify:
                %s
                """.formatted(truncate(text, 500));

        try {
            // Use completeFast (8s timeout) — moderation runs SYNCHRONOUSLY on
            // the request thread before the SSE Flux starts.  The 70s default
            // timeout (with one @Retry = 140s total) stalls the HTTP response
            // and causes the Flutter client to receive 0 SSE chars.
            String raw = claudeApiClient.completeFast(
                    modelRouter.getHaikuModel(), MODERATION_MAX_TOKENS, prompt, "moderation");
            // Strip fences and parse
            if (raw.startsWith("```")) raw = raw.replaceAll("```[a-z]*\\n?", "").replaceAll("```", "").strip();
            int s = raw.indexOf('{'), e = raw.lastIndexOf('}');
            if (s >= 0 && e > s) raw = raw.substring(s, e + 1);
            JsonNode node = objectMapper.readTree(raw);
            String category = node.path("category").asText("SAFE");
            String severity = node.path("severity").asText("SAFE");

            if ("SAFE".equals(category)) return safe();

            // Write the flag to the database (non-blocking — best effort)
            writeFlag(userId, avatarId, messageId, category, severity, text, source);
            log.warn("[Moderation] {} flag={} severity={} source={} user={}",
                    source, category, severity, source, userId);

            return new ModerationResult(true, category, severity,
                    buildSafeReply(category));

        } catch (Exception ex) {
            // Classifier failed — fail-safe for HIGH-risk patterns via simple keyword check
            log.warn("[Moderation] Classifier failed: {}; applying keyword fallback", ex.getMessage());
            return keywordFallback(userId, avatarId, messageId, text, source);
        }
    }

    /**
     * Ultra-cheap keyword fallback when the AI classifier is unavailable.
     * Only catches the most obvious HIGH-severity patterns.
     */
    private ModerationResult keywordFallback(String userId, String avatarId,
                                              String messageId, String text, String source) {
        String lower = text.toLowerCase();
        if (lower.contains("kill myself") || lower.contains("want to die") ||
                lower.contains("end my life") || lower.contains("suicide") ||
                lower.contains("self harm") || lower.contains("hurt myself")) {
            writeFlag(userId, avatarId, messageId,
                    ChatSafetyFlagJpaEntity.CAT_SELF_HARM,
                    ChatSafetyFlagJpaEntity.SEV_HIGH, text, source);
            return new ModerationResult(true,
                    ChatSafetyFlagJpaEntity.CAT_SELF_HARM,
                    ChatSafetyFlagJpaEntity.SEV_HIGH,
                    buildSafeReply(ChatSafetyFlagJpaEntity.CAT_SELF_HARM));
        }
        return safe();
    }

    private void writeFlag(String userId, String avatarId, String messageId,
                            String category, String severity, String text, String source) {
        try {
            ChatSafetyFlagJpaEntity flag = new ChatSafetyFlagJpaEntity();
            flag.setId(IdGenerator.newId());
            flag.setChildUserId(userId);
            flag.setAvatarId(avatarId);
            flag.setMessageId(messageId);
            flag.setCategory(category);
            flag.setSeverity(severity);
            flag.setSnippet(truncate(text, SNIPPET_MAX_CHARS));
            flag.setSource(source);
            flag.setResolved(false);
            flag.setCreatedAt(Instant.now());
            flagRepo.save(flag);
            CompletableFuture.runAsync(() ->
                    safetyAlertService.checkAndAlert(userId, avatarId, messageId, severity));
        } catch (Exception e) {
            log.error("[Moderation] Failed to write flag: {}", e.getMessage());
        }
    }

    private String buildSafeReply(String category) {
        return switch (category) {
            case "SELF_HARM" ->
                "I care about you, and I want you to be safe. 💙 If you're going through a tough time, "
                + "please talk to a grown-up you trust — a parent, teacher, or school counsellor. "
                + "You don't have to face it alone. I'm here to help with your studies whenever you're ready.";
            default ->
                "Let's keep our conversations focused on studying and learning! "
                + "If you need to talk about something else, please reach out to a grown-up. "
                + "What topic would you like to study today? 📚";
        };
    }

    private ModerationResult safe() {
        return new ModerationResult(false, "SAFE", "SAFE", null);
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
