package com.pally.domain.chat.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.TeachingMode;
import com.pally.domain.avatar.usecase.AvatarSlotGuard;
import com.pally.domain.chat.AssembledContext;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.SubscriptionTier;
import com.pally.infrastructure.persistence.assignment.ContentGapSignalJpaEntity;
import com.pally.infrastructure.persistence.assignment.ContentGapSignalJpaRepository;
import com.pally.infrastructure.persistence.module.LearningModuleJpaRepository;
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
import com.pally.domain.consent.ConsentGuard;
import com.pally.infrastructure.ai.CacheMetrics;
import com.pally.infrastructure.ai.ClaudeContextAssembler;
import com.pally.infrastructure.ai.ModerationService;
import com.pally.infrastructure.ai.ModelRouter;
import com.pally.shared.exception.AvatarNotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Use case: send a message to an avatar and receive a streaming response.
 *
 * <p>Merges prompt caching (Blocks 1-4) with Socratic dialogue logic.
 * Block 4 is dynamically modified based on teaching mode, hint trees, and attempt count.
 */
@Service
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
    private final ConsentGuard consentGuard;
    private final ModerationService moderationService;
    private final AvatarSlotGuard avatarSlotGuard;
    private final PremiumService premiumService;
    private final WikiRepository wikiRepository;
    private final LearningModuleJpaRepository learningModuleRepo;
    private final ContentGapSignalJpaRepository contentGapSignalRepo;

    @Value("${centre.closedbook.enabled:true}")
    private boolean closedBookEnabled;

    @Value("${centre.closedbook.relevance-threshold:0.55}")
    private double closedBookThreshold;

    @Value("${centre.closedbook.refusal-template:That's outside what {brand} covers in your materials. I can only answer from what your centre has uploaded. Ask your tutor, or try asking me about topics from your notes!}")
    private String closedBookRefusal;

    public SendMessageUseCase(
            AvatarRepository avatarRepository,
            ChatRepository chatRepository,
            ChatPort chatProxy,
            ClaudeContextAssembler contextAssembler,
            HintTreeRepository hintTreeRepository,
            ChatSessionRepository chatSessionRepository,
            TopicClassifier topicClassifier,
            SocraticPromptBuilder socraticPromptBuilder,
            ModelRouter modelRouter,
            ChatSessionSummariser sessionSummariser,
            ConsentGuard consentGuard,
            ModerationService moderationService,
            AvatarSlotGuard avatarSlotGuard,
            PremiumService premiumService,
            WikiRepository wikiRepository,
            LearningModuleJpaRepository learningModuleRepo,
            ContentGapSignalJpaRepository contentGapSignalRepo
    ) {
        this.avatarRepository = avatarRepository;
        this.chatRepository = chatRepository;
        this.chatProxy = chatProxy;
        this.contextAssembler = contextAssembler;
        this.hintTreeRepository = hintTreeRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.topicClassifier = topicClassifier;
        this.socraticPromptBuilder = socraticPromptBuilder;
        this.modelRouter = modelRouter;
        this.sessionSummariser = sessionSummariser;
        this.consentGuard = consentGuard;
        this.moderationService = moderationService;
        this.avatarSlotGuard = avatarSlotGuard;
        this.premiumService = premiumService;
        this.wikiRepository = wikiRepository;
        this.learningModuleRepo = learningModuleRepo;
        this.contentGapSignalRepo = contentGapSignalRepo;
    }

    public record StreamEvent(String type, String payload) {}

    /**
     * Backward-compatible overload for callers that do not pass a moduleId.
     */
    public Flux<StreamEvent> executeStream(String avatarId, String userId, String userMessage) {
        return executeStream(avatarId, userId, userMessage, null);
    }

    public Flux<StreamEvent> executeStream(String avatarId, String userId, String userMessage, String moduleId) {
        // Fix 2: Slot guard — locked avatars cannot be chatted with.
        avatarSlotGuard.requireActive(avatarId, userId);

        // Third-party AI consent gate (Apple 5.1.2 / PDPA overseas transfer).
        // Feature-flagged OFF by default — see ConsentGuard.requireAiConsent Javadoc.
        consentGuard.requireAiConsent(userId);

        // Resolve tier once at the start of the turn so the model selection
        // and logging are consistent within the same request.
        SubscriptionTier userTier;
        try {
            userTier = premiumService.resolveTier(userId);
        } catch (Exception ignored) {
            userTier = SubscriptionTier.FREE;
        }
        final SubscriptionTier tier = userTier;

        Avatar avatar = avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));

        // ── Centre avatar: locked check ──────────────────────────────────
        if (avatar.isAvatarLocked()) {
            String msg = "Your access to this Mochi has paused — check with your centre.";
            return Flux.just(new StreamEvent("token", msg), new StreamEvent("done", ""));
        }

        // ── Module-context confidence gate ──────────────────────────────
        // When chatting within a module context, ground to that module's wiki
        // page regardless of avatar type (personal or centre).
        if (moduleId != null && closedBookEnabled) {
            var moduleOpt = learningModuleRepo.findById(moduleId);
            if (moduleOpt.isPresent()) {
                var module = moduleOpt.get();
                String wikiAvatarId = avatar.getCorpusAvatarId() != null
                        ? avatar.getCorpusAvatarId() : avatarId;
                var wikiPageOpt = wikiRepository.findByAvatarIdAndSlug(
                        wikiAvatarId, module.getWikiPageSlug());
                if (wikiPageOpt.isPresent()) {
                    double relevance = computeKeywordRelevance(
                            userMessage, List.of(wikiPageOpt.get()));
                    if (relevance < closedBookThreshold) {
                        String refusal = "That's not covered in this topic's material. "
                                + "Try asking about " + module.getTitle()
                                + ", or upload more notes!";
                        log.info("[ModuleGate] REFUSED moduleId={} avatarId={} userId={} "
                                        + "relevance={} msg={}",
                                moduleId, avatarId, userId,
                                String.format("%.3f", relevance),
                                userMessage.substring(0, Math.min(60, userMessage.length())));

                        // Log as content gap signal for centre avatars
                        if (avatar.getClassId() != null) {
                            try {
                                ContentGapSignalJpaEntity gap = new ContentGapSignalJpaEntity();
                                gap.setId(com.pally.shared.util.IdGenerator.newId());
                                gap.setClassId(avatar.getClassId());
                                gap.setUserId(userId);
                                gap.setQueryText(userMessage.substring(
                                        0, Math.min(1000, userMessage.length())));
                                gap.setMatchedConfidence(
                                        java.math.BigDecimal.valueOf(relevance));
                                gap.setCreatedAt(java.time.Instant.now());
                                contentGapSignalRepo.save(gap);
                            } catch (Exception e) {
                                log.warn("[ModuleGate] Failed to save content gap signal: {}",
                                        e.getMessage());
                            }
                        }

                        boolean ephemeralCheck = consentGuard.isPending(userId);
                        if (!ephemeralCheck) {
                            ChatMessage refusedUserMsg = ChatMessage.create(avatarId, userId,
                                    ChatMessage.Role.USER, userMessage, null);
                            chatRepository.save(refusedUserMsg);
                            ChatMessage refusalMsg = ChatMessage.create(avatarId, userId,
                                    ChatMessage.Role.ASSISTANT, refusal, null);
                            chatRepository.save(refusalMsg);
                        }
                        return Flux.just(
                                new StreamEvent("delta", refusal),
                                new StreamEvent("done", ""));
                    }
                }
            }
        }

        // ── Centre avatar: closed-book gate ──────────────────────────────
        // Deterministic refuse — NO LLM call for off-corpus turns.
        if (closedBookEnabled && avatar.isCentreAvatar()) {
            // Centre avatars read the shared class corpus (held on the class's
            // corpus avatar), falling back to their own wiki if unlinked.
            String corpusId = avatar.getCorpusAvatarId() != null
                    ? avatar.getCorpusAvatarId() : avatarId;
            List<WikiPage> pages = wikiRepository.findActiveByAvatarId(corpusId);
            double relevance = computeKeywordRelevance(userMessage, pages);
            if (pages.isEmpty() || relevance < closedBookThreshold) {
                String brand = avatar.getName();
                String refusal = closedBookRefusal.replace("{brand}", brand);
                log.info("[ClosedBook] REFUSED orgAvatar={} userId={} relevance={} msg={}",
                        avatarId, userId, String.format("%.3f", relevance),
                        userMessage.substring(0, Math.min(60, userMessage.length())));
                // Persist both sides even for refused turns (ephemeral = pending-consent accounts)
                boolean ephemeralCheck = consentGuard.isPending(userId);
                if (!ephemeralCheck) {
                    ChatMessage refusedUserMsg = ChatMessage.create(avatarId, userId,
                            ChatMessage.Role.USER, userMessage, null);
                    chatRepository.save(refusedUserMsg);
                    ChatMessage refusalMsg = ChatMessage.create(avatarId, userId,
                            ChatMessage.Role.ASSISTANT, refusal, null);
                    chatRepository.save(refusalMsg);
                }
                return Flux.just(new StreamEvent("delta", refusal), new StreamEvent("done", ""));
            }
        }

        // PDPA: PENDING accounts chat ephemerally — skip persist to chat_message.
        boolean ephemeral = consentGuard.isPending(userId);

        // Moderation: screen the child's input BEFORE calling the model.
        // Fails safe: HIGH-severity → replace with caring redirect, never deliver raw content.
        ModerationService.ModerationResult inputMod = moderationService.screenInput(
                userId, avatarId, null, userMessage);
        if (inputMod.flagged() && inputMod.isHighSeverity()) {
            log.warn("[Chat] Input blocked by moderation cat={} user={}", inputMod.category(), userId);
            String safeReply = inputMod.safeReply();
            return Flux.just(
                    new StreamEvent("token", safeReply),
                    new StreamEvent("done", "")
            );
        }

        ChatMessage userMsg = ChatMessage.create(avatarId, userId, ChatMessage.Role.USER, userMessage, null);
        // Only persist if account is ACTIVE
        if (!ephemeral) chatRepository.save(userMsg);

        // Build prompt cache blocks 1-3 (stable)
        AssembledContext context = contextAssembler.assemble(avatar, userMessage);
        List<ChatMessage> history = chatRepository.findByAvatarId(avatarId, HISTORY_LIMIT);

        // ── Optional pre-stream steps — MUST NOT abort the turn ─────────────
        // Any failure here falls back to sane defaults so the main Claude
        // streaming call still runs. A DB hiccup on hint-tree lookup must
        // never show the user an empty "something went wrong" bubble.
        List<SocraticHintTree> allTrees = List.of();
        Optional<String> topicSlug = Optional.empty();
        Optional<SocraticHintTree> hintTree = Optional.empty();
        ChatSession session = ChatSession.createToday(avatarId);

        try {
            allTrees = hintTreeRepository.findByAvatarId(avatarId);
            topicSlug = topicClassifier.classify(userMessage, allTrees);
            hintTree = topicSlug
                    .flatMap(slug -> hintTreeRepository.findByAvatarIdAndSlug(avatarId, slug));
            session = chatSessionRepository
                    .findByAvatarIdAndDate(avatarId, LocalDate.now())
                    .orElseGet(() -> ChatSession.createToday(avatarId));
        } catch (Exception e) {
            log.warn("[Chat] Optional pre-stream steps failed for avatar={} — using defaults: {}",
                    avatarId, e.getMessage());
        }

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

        try {
            chatSessionRepository.save(session);
        } catch (Exception e) {
            log.warn("[Chat] Session save failed for avatar={} (non-fatal): {}", avatarId, e.getMessage());
        }

        // Build Block 4 (dynamic tail — no cache) based on Socratic state
        Map<String, Object> block4 = socraticPromptBuilder.buildBlock4(
                mode, hintTree, session.getAttemptCount(), shouldEscape || deflecting);

        // Replace Block 4 in the system blocks list
        List<Map<String, Object>> systemBlocks = buildBlocksWithSocraticTail(
                context.systemBlocks(), block4);

        // ── Centre avatar: prepend closed-book constraint as Block 0 ────
        if (avatar.isCentreAvatar()) {
            Map<String, Object> closedBookBlock = Map.of("type", "text", "text",
                    """
                    CENTRE TUTOR CONSTRAINT — NON-NEGOTIABLE:
                    You are %s, a centre tutor. Answer ONLY from the KNOWLEDGE BASE provided in this prompt.
                    If the answer is not clearly in the knowledge base: say "That's outside what I've been given to teach — ask your tutor!" and stop.
                    Do NOT use outside knowledge. Do NOT guess. Do NOT answer from training data.
                    Cite which topic/page your answer comes from.
                    """.formatted(avatar.getName()));
            List<Map<String, Object>> withConstraint = new ArrayList<>();
            withConstraint.add(closedBookBlock);
            withConstraint.addAll(systemBlocks);
            systemBlocks = withConstraint;
        }

        // ── Chat context diagnostic log ────────────────────────────────────
        // INFO so it shows up in Railway on every turn. Tells you what the
        // model receives: history depth, session memory, topic routing.
        log.info("[ChatCtx] avatarId={} historyMsgs={} mode={} topic={} escape={}",
                avatarId, history.size(), mode, topicSlug.orElse("none"),
                shouldEscape || deflecting);
        if (history.size() > 0) {
            // Log the last user message in history so we can see topic drift
            history.stream()
                    .filter(m -> m.getRole() == com.pally.domain.chat.ChatMessage.Role.USER)
                    .reduce((a, b) -> b)
                    .ifPresent(last -> log.info("[ChatCtx] Last history user msg: '{}'",
                            last.getContent() == null ? "(null)"
                            : last.getContent().substring(0, Math.min(80, last.getContent().length()))));
        }

        StringBuilder replyBuffer = new StringBuilder();
        AtomicReference<String> assistantMessageId = new AtomicReference<>();
        AtomicReference<CacheMetrics> capturedMetrics = new AtomicReference<>();
        AtomicReference<String> capturedSourceFile = new AtomicReference<>();

        String selectedModel = modelRouter.forChat(userMessage, tier);
        return chatProxy.streamChat(systemBlocks, history, userMessage, capturedMetrics::set, selectedModel)
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
                        String cleanReply = SOURCE_TRAILER.matcher(
                                replyBuffer.toString()).replaceFirst("").trim();

                        // Output moderation is intentionally NOT called here.
                        // Reason: doOnComplete() runs on a reactor-http-epoll thread.
                        // completeFast() calls .block() which is forbidden on reactor
                        // threads → FAST FAILED every turn → keyword fallback only →
                        // moderation never actually ran. The SSE stream has already been
                        // fully emitted to the client by the time doOnComplete fires, so
                        // we can't replace the content anyway. Removing this saves one
                        // failed call attempt per message (the 1ms FAST FAILED log).
                        // TODO: buffer the stream, screen before emit (requires reactive rewrite).

                        // PENDING accounts: skip persisting the assistant reply.
                        if (ephemeral) {
                            log.debug("[Chat] Ephemeral (PENDING) — skipping persist user={}", userId);
                            return;
                        }

                        ChatMessage assistantMsg = ChatMessage.createWithTrace(
                                avatarId, userId, ChatMessage.Role.ASSISTANT,
                                cleanReply, capturedSourceFile.get(),
                                context.harnessTrace()
                        );
                        ChatMessage saved = chatRepository.save(assistantMsg);
                        assistantMessageId.set(saved.getId());

                        try {
                            chatRepository.updateModelUsed(saved.getId(), selectedModel);
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

                        // Rolling-summary — throttled to every 3 turns.
                        // Previously ran every turn: ~600-2700 input tokens/call
                        // → pure cost leak (~$0.0005/turn wasted). history.size()
                        // is the number of prior messages; (size+1) is the turn
                        // index. Runs on turns 0, 3, 6, 9 … (multiples of 3).
                        // Keeps memory fresh within a few exchanges while cutting
                        // summary cost by ~67%.
                        if (history.size() % 3 == 0) {
                            sessionSummariser.updateSummary(
                                    avatarId, userMessage, cleanReply);
                        }
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

    /**
     * Lightweight keyword-overlap relevance score between a query and a wiki corpus.
     * Returns a value in [0.0, 1.0] where 1.0 means every query token appears in at
     * least one wiki page. Used for deterministic closed-book gating — never calls LLM.
     */
    double computeKeywordRelevance(String query, List<WikiPage> pages) {
        if (pages.isEmpty() || query == null || query.isBlank()) return 0.0;
        Set<String> queryTokens = tokenise(query);
        if (queryTokens.isEmpty()) return 0.0;
        return pages.stream().mapToDouble(page -> {
            Set<String> pageTokens = tokenise(page.getContent() + " " + page.getTitle());
            long matches = queryTokens.stream().filter(pageTokens::contains).count();
            return (double) matches / queryTokens.size();
        }).max().orElse(0.0);
    }

    /**
     * Lowercases, strips punctuation, splits on whitespace, and removes tokens
     * shorter than 3 characters (noise stopwords).
     */
    private Set<String> tokenise(String text) {
        if (text == null || text.isBlank()) return Set.of();
        return Arrays.stream(text.toLowerCase().replaceAll("[^a-z0-9\\s]", " ").split("\\s+"))
                .filter(w -> w.length() > 2)
                .collect(Collectors.toSet());
    }
}
