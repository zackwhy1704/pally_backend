package com.pally.domain.chat.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
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
import com.pally.domain.chat.ChatStreamEvent;
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
 * Tests that a centre avatar reads the CORPUS wiki (via corpusAvatarId),
 * not its own wiki. Also tests the closed-book refusal path.
 */
@ExtendWith(MockitoExtension.class)
class SendMessageUseCaseCorpusRedirectTest {

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

    private SendMessageUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new SendMessageUseCase(
                avatarRepository, chatRepository, chatPort, contextAssembler,
                hintTreeRepository, chatSessionRepository, topicClassifier,
                socraticPromptBuilder, modelRouter, sessionSummariser,
                consentGuard, moderationService, avatarSlotGuard,
                premiumService, wikiRepository);
        ReflectionTestUtils.setField(useCase, "closedBookEnabled", true);
        ReflectionTestUtils.setField(useCase, "closedBookThreshold", 0.55);
        ReflectionTestUtils.setField(useCase, "closedBookRefusal",
                "That's outside what {brand} covers.");

        lenient().doNothing().when(avatarSlotGuard).requireActive(anyString(), anyString());
        lenient().when(premiumService.resolveTier(anyString())).thenReturn(SubscriptionTier.FREE);
        lenient().when(moderationService.screenInput(anyString(), anyString(), any(), anyString()))
                .thenReturn(new ModerationService.ModerationResult(false, "SAFE", "SAFE", null));
        lenient().when(consentGuard.isPending(anyString())).thenReturn(false);
        lenient().when(modelRouter.forChat(anyString(), any())).thenReturn("claude-haiku-4-5-20251001");
    }

    @Test
    void centreAvatar_withCorpusId_readsCorpusWiki() {
        // Centre avatar with corpusAvatarId points to the corpus
        Avatar centreAvatar = Avatar.reconstitute(
                "student-avatar", "user-1", "Centre Math", Subject.MATHS, CharacterType.MOCHI,
                0, java.time.Instant.now(), null, null, Avatar.PedagogyMode.SOCRATIC,
                com.pally.domain.avatar.TeachingMode.TEACHING, null,
                Avatar.BrainState.READY, true, null, true, false);
        centreAvatar.setCorpusAvatarId("corpus-avatar-id");

        when(avatarRepository.findById("student-avatar")).thenReturn(Optional.of(centreAvatar));
        // The closed-book gate reads the CORPUS wiki (corpusAvatarId)
        WikiPage corpusPage = WikiPage.create("corpus-avatar-id", "fractions",
                "Fractions", "A fraction has numerator and denominator");
        when(wikiRepository.findActiveByAvatarId("corpus-avatar-id"))
                .thenReturn(List.of(corpusPage));

        // Full chat flow mocks needed after passing closed-book gate
        when(chatRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chatRepository.findByAvatarId(anyString(), anyInt())).thenReturn(List.of());
        when(contextAssembler.assemble(any(Avatar.class), anyString()))
                .thenReturn(new com.pally.domain.chat.AssembledContext(
                        "prompt", "trace",
                        List.of(Map.of("type", "text", "text", "prompt"), Map.of("type", "text", "text", "block4"))));
        when(hintTreeRepository.findByAvatarId(anyString())).thenReturn(List.of());
        when(topicClassifier.classify(anyString(), anyList())).thenReturn(Optional.empty());
        when(topicClassifier.detectsDeflection(anyString())).thenReturn(false);
        when(chatSessionRepository.findByAvatarIdAndDate(anyString(), any())).thenReturn(Optional.empty());
        when(socraticPromptBuilder.buildBlock4(any(), any(), anyInt(), anyBoolean()))
                .thenReturn(Map.of("type", "text", "text", "block4"));
        when(chatPort.streamChat(anyList(), anyList(), anyString(), any(), anyString()))
                .thenReturn(Flux.just(
                        new ChatStreamEvent.Token("Here is the answer"),
                        new ChatStreamEvent.Done(null)));

        Flux<SendMessageUseCase.StreamEvent> stream =
                useCase.executeStream("student-avatar", "user-1", "explain fractions numerator denominator");

        StepVerifier.create(stream)
                .expectNextMatches(e -> "delta".equals(e.type()))
                .expectNextMatches(e -> "done".equals(e.type()))
                .verifyComplete();

        // Verify the wiki lookup was on the corpus
        verify(wikiRepository).findActiveByAvatarId("corpus-avatar-id");
    }

    @Test
    void centreAvatar_offTopicQuery_returnsRefusal() {
        Avatar centreAvatar = Avatar.reconstitute(
                "student-avatar", "user-1", "Centre Math", Subject.MATHS, CharacterType.MOCHI,
                0, java.time.Instant.now(), null, null, Avatar.PedagogyMode.SOCRATIC,
                com.pally.domain.avatar.TeachingMode.TEACHING, null,
                Avatar.BrainState.READY, true, null, true, false);
        centreAvatar.setCorpusAvatarId("corpus-avatar-id");

        when(avatarRepository.findById("student-avatar")).thenReturn(Optional.of(centreAvatar));
        WikiPage corpusPage = WikiPage.create("corpus-avatar-id", "fractions",
                "Fractions", "A fraction has numerator and denominator");
        when(wikiRepository.findActiveByAvatarId("corpus-avatar-id"))
                .thenReturn(List.of(corpusPage));

        // Off-topic query — no keyword overlap with the wiki
        Flux<SendMessageUseCase.StreamEvent> stream =
                useCase.executeStream("student-avatar", "user-1",
                        "what is the capital of Mongolia");

        StepVerifier.create(stream)
                .expectNextMatches(e -> "delta".equals(e.type())
                        && e.payload().contains("outside what Centre Math covers"))
                .expectNextMatches(e -> "done".equals(e.type()))
                .verifyComplete();
    }

    @Test
    void centreAvatar_emptyCorpus_returnsRefusal() {
        Avatar centreAvatar = Avatar.reconstitute(
                "student-avatar", "user-1", "Centre Math", Subject.MATHS, CharacterType.MOCHI,
                0, java.time.Instant.now(), null, null, Avatar.PedagogyMode.SOCRATIC,
                com.pally.domain.avatar.TeachingMode.TEACHING, null,
                Avatar.BrainState.READY, true, null, true, false);
        centreAvatar.setCorpusAvatarId("corpus-avatar-id");

        when(avatarRepository.findById("student-avatar")).thenReturn(Optional.of(centreAvatar));
        when(wikiRepository.findActiveByAvatarId("corpus-avatar-id")).thenReturn(List.of());

        // Empty corpus → always fails the closed-book gate
        Flux<SendMessageUseCase.StreamEvent> stream =
                useCase.executeStream("student-avatar", "user-1", "what are fractions");

        StepVerifier.create(stream)
                .expectNextMatches(e -> "delta".equals(e.type())
                        && e.payload().contains("outside what Centre Math covers"))
                .expectNextMatches(e -> "done".equals(e.type()))
                .verifyComplete();
    }
}
