package com.pally.domain.chat.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.avatar.TeachingMode;
import com.pally.domain.avatar.usecase.AvatarSlotGuard;
import com.pally.domain.chat.ChatMessage;
import com.pally.domain.chat.ChatRepository;
import com.pally.domain.chat.ChatSession;
import com.pally.domain.chat.ChatSessionRepository;
import com.pally.domain.chat.ChatSessionSummariser;
import com.pally.domain.chat.ChatStreamEvent;
import com.pally.domain.chat.HintTreeRepository;
import com.pally.domain.chat.SocraticPromptBuilder;
import com.pally.domain.chat.TopicClassifier;
import com.pally.domain.chat.AssembledContext;
import com.pally.domain.chat.port.ChatPort;
import com.pally.domain.consent.ConsentGuard;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.SubscriptionTier;
import com.pally.infrastructure.ai.ClaudeContextAssembler;
import com.pally.infrastructure.ai.ModerationService;
import com.pally.infrastructure.ai.ModelRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests the "open path" (personal avatar) through SendMessageUseCase.
 * Personal avatars assemble their own wiki and call Claude.
 */
@ExtendWith(MockitoExtension.class)
class SendMessageUseCaseOpenPathTest {

    @Mock private AvatarRepository avatarRepository;
    @Mock private ChatRepository chatRepository;
    @Mock private ChatPort chatPort;
    @Mock private ClaudeContextAssembler contextAssembler;
    @Mock private HintTreeRepository hintTreeRepository;
    @Mock private ChatSessionRepository chatSessionRepository;
    @Mock private TopicClassifier topicClassifier;
    @Mock private SocraticPromptBuilder socraticPromptBuilder;
    @Mock private ModelRouter modelRouter;
    @Mock private ChatSessionSummariser sessionSummariser;
    @Mock private ConsentGuard consentGuard;
    @Mock private ModerationService moderationService;
    @Mock private AvatarSlotGuard avatarSlotGuard;
    @Mock private PremiumService premiumService;
    @Mock private WikiRepository wikiRepository;
    @Mock private com.pally.domain.module.LearningModuleRepository learningModuleRepo;
    @Mock private com.pally.domain.assignment.ContentGapSignalRepository contentGapSignalRepo;

    private SendMessageUseCase useCase;
    private Avatar personalAvatar;

    @BeforeEach
    void setUp() {
        useCase = new SendMessageUseCase(
                avatarRepository, chatRepository, chatPort, contextAssembler,
                hintTreeRepository, chatSessionRepository, topicClassifier,
                socraticPromptBuilder, modelRouter, sessionSummariser,
                consentGuard, moderationService, avatarSlotGuard,
                premiumService, wikiRepository,
                learningModuleRepo, contentGapSignalRepo);
        ReflectionTestUtils.setField(useCase, "closedBookEnabled", true);
        ReflectionTestUtils.setField(useCase, "closedBookThreshold", 0.55);
        ReflectionTestUtils.setField(useCase, "closedBookRefusal",
                "That's outside what {brand} covers.");

        personalAvatar = Avatar.create("user-1", "MathBot", Subject.MATHS, CharacterType.MOCHI);

        // Common stubs
        lenient().doNothing().when(avatarSlotGuard).requireActive(anyString(), anyString());
        lenient().when(premiumService.resolveTier(anyString())).thenReturn(SubscriptionTier.FREE);
        lenient().when(premiumService.resolveTierContext(anyString()))
                .thenReturn(new PremiumService.TierContext(SubscriptionTier.FREE, false));
        lenient().when(moderationService.screenInput(anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(new ModerationService.ModerationResult(false, "SAFE", "SAFE", null));
        lenient().when(consentGuard.isPending(anyString())).thenReturn(false);
        lenient().when(modelRouter.forChat(anyString(), any())).thenReturn("claude-haiku-4-5-20251001");
        lenient().when(modelRouter.forChat(anyString(), any(), anyBoolean())).thenReturn("claude-haiku-4-5-20251001");
    }

    @Test
    void personalAvatar_routesToOpenPath_callsClaude() {
        when(avatarRepository.findById("avatar-1")).thenReturn(Optional.of(personalAvatar));
        when(chatRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chatRepository.findByAvatarId(anyString(), anyInt())).thenReturn(List.of());
        when(contextAssembler.assemble(any(Avatar.class), anyString()))
                .thenReturn(new AssembledContext(
                        "system prompt", "trace",
                        List.of(Map.of("type", "text", "text", "system prompt"))));
        when(hintTreeRepository.findByAvatarId(anyString())).thenReturn(List.of());
        when(topicClassifier.classify(anyString(), anyList())).thenReturn(Optional.empty());
        when(topicClassifier.detectsDeflection(anyString())).thenReturn(false);
        when(chatSessionRepository.findByAvatarIdAndDate(anyString(), any()))
                .thenReturn(Optional.of(ChatSession.createToday("avatar-1")));
        when(socraticPromptBuilder.buildBlock4(any(), any(), anyInt(), anyBoolean()))
                .thenReturn(Map.of("type", "text", "text", "block4"));
        when(chatPort.streamChat(anyList(), anyList(), anyString(), any(), anyString()))
                .thenReturn(Flux.just(
                        new ChatStreamEvent.Token("Hello!"),
                        new ChatStreamEvent.Done(null)));

        Flux<SendMessageUseCase.StreamEvent> stream =
                useCase.executeStream("avatar-1", "user-1", "What is 2+2?");

        StepVerifier.create(stream)
                .expectNextMatches(e -> "delta".equals(e.type()) && "Hello!".equals(e.payload()))
                .expectNextMatches(e -> "done".equals(e.type()))
                .verifyComplete();

        verify(chatPort).streamChat(anyList(), anyList(), eq("What is 2+2?"), any(), anyString());
    }

    @Test
    void personalAvatar_emptyWiki_stillRoutesToClaude() {
        // Personal avatars should work even with no wiki pages
        when(avatarRepository.findById("avatar-1")).thenReturn(Optional.of(personalAvatar));
        when(chatRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chatRepository.findByAvatarId(anyString(), anyInt())).thenReturn(List.of());
        when(contextAssembler.assemble(any(Avatar.class), anyString()))
                .thenReturn(new AssembledContext(
                        "empty wiki", "trace",
                        List.of(Map.of("type", "text", "text", "empty wiki"))));
        when(hintTreeRepository.findByAvatarId(anyString())).thenReturn(List.of());
        when(topicClassifier.classify(anyString(), anyList())).thenReturn(Optional.empty());
        when(topicClassifier.detectsDeflection(anyString())).thenReturn(false);
        when(chatSessionRepository.findByAvatarIdAndDate(anyString(), any()))
                .thenReturn(Optional.empty());
        when(socraticPromptBuilder.buildBlock4(any(), any(), anyInt(), anyBoolean()))
                .thenReturn(Map.of("type", "text", "text", "block4"));
        when(chatPort.streamChat(anyList(), anyList(), anyString(), any(), anyString()))
                .thenReturn(Flux.just(
                        new ChatStreamEvent.Token("I can help!"),
                        new ChatStreamEvent.Done(null)));

        Flux<SendMessageUseCase.StreamEvent> stream =
                useCase.executeStream("avatar-1", "user-1", "Help me");

        StepVerifier.create(stream)
                .expectNextMatches(e -> "delta".equals(e.type()))
                .expectNextMatches(e -> "done".equals(e.type()))
                .verifyComplete();
    }
}
