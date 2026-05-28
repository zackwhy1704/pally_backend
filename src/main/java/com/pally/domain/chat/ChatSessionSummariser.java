package com.pally.domain.chat;

import com.pally.infrastructure.ai.ClaudeApiClient;
import com.pally.infrastructure.ai.ModelRouter;
import com.pally.infrastructure.persistence.chat.ChatSessionSummaryJpaEntity;
import com.pally.infrastructure.persistence.chat.ChatSessionSummaryJpaRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Maintains a rolling forward-looking summary for each avatar's chat history.
 *
 * <p>After every chat turn (called fire-and-forget from
 * {@link com.pally.domain.chat.usecase.SendMessageUseCase}), this asks Claude
 * Haiku to compress the prior summary + latest exchange into a fresh ≤150-word
 * note covering: topics covered, where the child struggled, what to focus on
 * next. The note is then injected into the next session's system prompt by
 * {@link com.pally.infrastructure.ai.ClaudeContextAssembler}.
 *
 * <p>Failures here are intentionally swallowed — a missing or stale summary
 * never breaks the chat flow, it just means the tutor remembers a little
 * less next time.
 */
@Service
@RequiredArgsConstructor
public class ChatSessionSummariser {

    private static final Logger log =
            LoggerFactory.getLogger(ChatSessionSummariser.class);
    private static final int MAX_TOKENS = 256;

    private final ChatSessionSummaryJpaRepository summaryRepo;
    private final ClaudeApiClient apiClient;
    private final ModelRouter modelRouter;

    /** Returns the latest stored summary for {@code avatarId}, or empty. */
    @Transactional(readOnly = true)
    public Optional<String> findSummary(String avatarId) {
        return summaryRepo.findByAvatarId(avatarId)
                .map(ChatSessionSummaryJpaEntity::getSummary);
    }

    /**
     * Updates the rolling summary based on the latest user/assistant turn.
     * Runs async so it does not delay the user's response.
     */
    @Async
    @Transactional
    public void updateSummary(String avatarId, String userMessage,
                              String assistantReply) {
        if (userMessage == null || userMessage.isBlank()) return;
        if (assistantReply == null || assistantReply.isBlank()) return;

        try {
            String prior = summaryRepo.findByAvatarId(avatarId)
                    .map(ChatSessionSummaryJpaEntity::getSummary)
                    .orElse("");

            String prompt = buildPrompt(prior, userMessage, assistantReply);
            String newSummary = apiClient.complete(
                    modelRouter.getHaikuModel(), MAX_TOKENS, prompt);
            if (newSummary == null || newSummary.isBlank()) return;

            ChatSessionSummaryJpaEntity entity = summaryRepo
                    .findByAvatarId(avatarId)
                    .orElseGet(ChatSessionSummaryJpaEntity::new);
            entity.setAvatarId(avatarId);
            entity.setSummary(newSummary.trim());
            entity.setUpdatedAt(Instant.now());
            summaryRepo.save(entity);
            log.debug("[SessionSummary] Updated avatarId={} chars={}",
                    avatarId, newSummary.length());
        } catch (Exception e) {
            // Best effort — a failed summary update must never affect chat.
            log.warn("[SessionSummary] Update failed avatarId={}: {}",
                    avatarId, e.getMessage());
        }
    }

    private String buildPrompt(String prior, String userMessage,
                                String assistantReply) {
        return """
                You are maintaining a rolling memory note for a children's tutor.
                The note is read back at the start of the next conversation so the
                tutor can pick up where it left off — DO NOT chat with the child
                here, only update the memory.

                ## Existing memory (may be empty)
                %s

                ## Latest exchange
                Child: %s
                Tutor: %s

                ## Task
                Produce an updated memory note (max 150 words) covering:
                - Topics covered so far
                - Where the child struggled or got stuck
                - What to focus on next session
                - Any preferences / hints the tutor should remember

                Reply with the updated note only — no preamble, no markdown headers,
                no quotes.
                """.formatted(
                        prior.isBlank() ? "(none — this is the first session)" : prior,
                        truncate(userMessage, 800),
                        truncate(assistantReply, 1200));
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
