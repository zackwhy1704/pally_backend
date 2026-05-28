package com.pally.domain.chat.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.TeachingMode;
import com.pally.domain.chat.AssembledContext;
import com.pally.domain.chat.ChatMessage;
import com.pally.domain.chat.ChatRepository;
import com.pally.domain.chat.ChatSession;
import com.pally.domain.chat.ChatSessionRepository;
import com.pally.domain.chat.ChatSessionSummariser;
import com.pally.domain.chat.ChatStreamEvent;
import com.pally.domain.chat.HintTreeRepository;
import com.pally.domain.chat.SocraticHintTree;
import com.pally.domain.chat.SocraticPromptBuilder;
import com.pally.domain.chat.TopicClassifier;
import com.pally.domain.chat.port.ChatPort;
import com.pally.infrastructure.ai.CacheMetrics;
import com.pally.infrastructure.ai.ClaudeContextAssembler;
import com.pally.infrastructure.ai.ModelRouter;
import com.pally.shared.exception.AvatarNotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Use case: send a message to an avatar and receive a streaming response.
 *
 * <p>Merges prompt caching (Blocks 1-4) with Socratic dialogue logic.
 * Block 4 is dynamically modified based on teaching mode, hint trees, and attempt count.
 */
@Service
@RequiredArgsConstructor
public class SendMessageUseCase {

    private static final Logger log = LoggerFactory.getLogger(SendMessageUseCase.class);
    private static final int HISTORY_LIMIT = 20;
    // Strips the trailing "SOURCE: <slug>" line Claude appends so the
    // persisted message contains only the visible body. The slug is captured
    // separately and stored as ChatMessage.sourceFile.
    private static final Pattern SOURCE_TRAILER = Pattern.compile(
            "\\s*\\n*SOURCE\\s*:?\\s*\\[?[a-zA-Z0-9_\\-/]+\\]?\\s*$");

    private final AvatarRepository avatarRepository;
    private final ChatRepository chatRepository;
    private final ChatPort chatProxy;
    private final ClaudeContextAssembler contextAssembler;
    private final HintTreeRepository hintTreeRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final TopicClassifier topicClassifier;
    private final SocraticPromptBuilder socraticPromptBuilder;
    private final ModelRouter modelRouter;
    private final ChatSessionSummariser sessionSummariser;

    public record StreamEvent(String type, String payload) {}

    public Flux<StreamEvent> executeStream(String avatarId, String userId, String userMessage) {
        Avatar avatar = avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));

        ChatMessage userMsg = ChatMessage.create(avatarId, userId, ChatMessage.Role.USER, userMessage, null);
        chatRepository.save(userMsg);

        // Build prompt cache blocks 1-3 (stable)
        AssembledContext context = contextAssembler.assemble(avatar, userMessage);
        List<ChatMessage> history = chatRepository.findByAvatarId(avatarId, HISTORY_LIMIT);

        // Socratic: classify topic and get/update session
        List<SocraticHintTree> allTrees = hintTreeRepository.findByAvatarId(avatarId);
        Optional<String> topicSlug = topicClassifier.classify(userMessage, allTrees);
        Optional<SocraticHintTree> hintTree = topicSlug
                .flatMap(slug -> hintTreeRepository.findByAvatarIdAndSlug(avatarId, slug));

        ChatSession session = chatSessionRepository
                .findByAvatarIdAndDate(avatarId, LocalDate.now())
                .orElseGet(() -> ChatSession.createToday(avatarId));

        // Record attempt and check escape hatch
        topicSlug.ifPresent(session::recordAttempt);
        if (topicSlug.isEmpty()) session.recordAttempt(null);

        TeachingMode mode = avatar.getTeachingMode();
        boolean shouldEscape = session.shouldEscape(mode);
        boolean deflecting = topicClassifier.detectsDeflection(userMessage);

        if (deflecting) {
            log.debug("[Socratic] Deflection detected for avatar={} — forcing escape hatch", avatarId);
        }

        if (shouldEscape || deflecting) {
            session.markEscapeFired();
        }

        chatSessionRepository.save(session);

        // Build Block 4 (dynamic tail — no cache) based on Socratic state
        Map<String, Object> block4 = socraticPromptBuilder.buildBlock4(
                mode, hintTree, session.getAttemptCount(), shouldEscape || deflecting);

        // Replace Block 4 in the system blocks list
        List<Map<String, Object>> systemBlocks = buildBlocksWithSocraticTail(
                context.systemBlocks(), block4);

        log.debug("[Chat] avatarId={} historySize={} mode={} attempts={} escape={} topic={}",
                avatarId, history.size(), mode, session.getAttemptCount(),
                shouldEscape || deflecting, topicSlug.orElse("none"));

        StringBuilder replyBuffer = new StringBuilder();
        AtomicReference<String> assistantMessageId = new AtomicReference<>();
        AtomicReference<CacheMetrics> capturedMetrics = new AtomicReference<>();
        AtomicReference<String> capturedSourceFile = new AtomicReference<>();

        return chatProxy.streamChat(systemBlocks, history, userMessage, capturedMetrics::set)
                .doOnNext(event -> {
                    if (event instanceof ChatStreamEvent.Token token) {
                        replyBuffer.append(token.text());
                    } else if (event instanceof ChatStreamEvent.Done done) {
                        capturedSourceFile.set(done.sourceFile());
                    }
                })
                .doOnComplete(() -> {
                    if (!replyBuffer.isEmpty()) {
                        // Strip the "SOURCE: <slug>" trailer the model appends
                        // per system prompt — we already carry the slug as a
                        // dedicated field, so the bubble text must not leak it.
                        String cleanReply = SOURCE_TRAILER.matcher(
                                replyBuffer.toString()).replaceFirst("").trim();
                        ChatMessage assistantMsg = ChatMessage.createWithTrace(
                                avatarId, userId, ChatMessage.Role.ASSISTANT,
                                cleanReply, capturedSourceFile.get(),
                                context.harnessTrace()
                        );
                        ChatMessage saved = chatRepository.save(assistantMsg);
                        assistantMessageId.set(saved.getId());

                        try {
                            chatRepository.updateModelUsed(
                                    saved.getId(), modelRouter.forChat(userMessage));
                        } catch (Exception e) {
                            log.warn("[Chat] Failed to save model_used for message={}", saved.getId());
                        }

                        CacheMetrics metrics = capturedMetrics.get();
                        if (metrics != null) {
                            try {
                                chatRepository.updateCacheMetrics(
                                        saved.getId(),
                                        metrics.wasCacheHit(),
                                        metrics.cacheReadInputTokens(),
                                        metrics.cacheCreationInputTokens(),
                                        metrics.inputTokens(),
                                        metrics.outputTokens()
                                );
                                log.info("[Cache] Turn saved ~${} in input cost",
                                        String.format("%.4f", metrics.estimateSavingUsd(3.00)));
                            } catch (Exception e) {
                                log.warn("[Cache] Failed to save metrics for message={}: {}",
                                        saved.getId(), e.getMessage());
                            }
                        }

                        log.debug("[Chat] Saved reply avatarId={} chars={}", avatarId, replyBuffer.length());

                        // Fire-and-forget rolling-summary update so the
                        // tutor remembers this exchange on the next turn.
                        sessionSummariser.updateSummary(
                                avatarId, userMessage, cleanReply);
                    }
                })
                .map(event -> switch (event) {
                    case ChatStreamEvent.Token t -> new StreamEvent("delta", t.text());
                    case ChatStreamEvent.Done d  -> new StreamEvent("done",
                            d.sourceFile() != null ? d.sourceFile() : "");
                    case ChatStreamEvent.Error e -> new StreamEvent("error", e.message());
                });
    }

    /**
     * Replaces the last block (Block 4) with the dynamically-built Socratic tail.
     * If no blocks exist yet (empty context), just returns [block4].
     */
    private List<Map<String, Object>> buildBlocksWithSocraticTail(
            List<Map<String, Object>> existing, Map<String, Object> block4) {
        if (existing.isEmpty()) {
            return List.of(block4);
        }
        List<Map<String, Object>> result = new ArrayList<>(existing.subList(0, existing.size() - 1));
        result.add(block4);
        return result;
    }
}
