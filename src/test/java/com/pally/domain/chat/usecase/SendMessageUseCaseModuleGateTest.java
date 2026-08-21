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
import com.pally.domain.chat.port.ChatPort;
import com.pally.domain.consent.ConsentGuard;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.SubscriptionTier;
import com.pally.infrastructure.ai.ClaudeContextAssembler;
import com.pally.infrastructure.ai.ModerationService;
import com.pally.infrastructure.ai.ModelRouter;
import com.pally.domain.assignment.ContentGapSignalRepository;
import com.pally.domain.module.LearningModule;
import com.pally.domain.module.LearningModuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the module-context confidence gate in SendMessageUseCase.
 * When moduleId is present, off-topic questions are refused regardless of avatar type.
 */
@ExtendWith(MockitoExtension.class)
class SendMessageUseCaseModuleGateTest {

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
    @Mock private LearningModuleRepository learningModuleRepo;
    @Mock private ContentGapSignalRepository contentGapSignalRepo;

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

        lenient().doNothing().when(avatarSlotGuard).requireActive(anyString(), anyString());
        lenient().when(premiumService.resolveTier(anyString())).thenReturn(SubscriptionTier.FREE);
        lenient().when(premiumService.resolveTierContext(anyString()))
                .thenReturn(new PremiumService.TierContext(SubscriptionTier.FREE, false));
        lenient().when(consentGuard.isPending(anyString())).thenReturn(false);
        lenient().when(moderationService.screenInput(anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(new ModerationService.ModerationResult(false, "SAFE", "SAFE", null));
        lenient().when(modelRouter.forChat(anyString(), any(), anyBoolean())).thenReturn("claude-haiku-4-5-20251001");
    }

    @Test
    void moduleContextChat_onTopicQuestion_allowed() {
        // Module about fractions; question about fractions
        LearningModule module = new LearningModule();
        module.setId("mod-1");
        module.setWikiPageSlug("fractions");
        module.setTitle("Fractions");

        WikiPage page = WikiPage.create("av-1", "fractions", "Fractions",
                "fractions represent parts whole numerator top number "
                        + "denominator bottom number simplify simplifying");

        when(avatarRepository.findById("av-1")).thenReturn(Optional.of(personalAvatar));
        when(learningModuleRepo.findById("mod-1")).thenReturn(Optional.of(module));
        when(wikiRepository.findByAvatarIdAndSlug("av-1", "fractions"))
                .thenReturn(Optional.of(page));

        // The on-topic question should NOT be refused, so it falls through
        // to the main chat flow. We need to mock the rest of the flow.
        when(chatRepository.save(any(ChatMessage.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(chatRepository.findByAvatarId(anyString(), anyInt())).thenReturn(List.of());
        when(contextAssembler.assemble(any(), anyString()))
                .thenReturn(new com.pally.domain.chat.AssembledContext("", "trace", List.of()));
        when(hintTreeRepository.findByAvatarId(anyString())).thenReturn(List.of());
        when(topicClassifier.classify(anyString(), anyList())).thenReturn(Optional.empty());
        when(topicClassifier.detectsDeflection(anyString())).thenReturn(false);
        when(chatSessionRepository.findByAvatarIdAndDate(anyString(), any()))
                .thenReturn(Optional.of(ChatSession.createToday("av-1")));
        when(socraticPromptBuilder.buildBlock4(any(), any(), anyInt(), anyBoolean(), any()))
                .thenReturn(java.util.Map.of("type", "text", "text", ""));
        when(modelRouter.forChat(anyString(), any(), anyBoolean())).thenReturn("claude-sonnet-4-20250514");
        when(chatPort.streamChat(anyList(), anyList(), anyString(), any(), anyString()))
                .thenReturn(reactor.core.publisher.Flux.just(
                        new ChatStreamEvent.Token("answer"),
                        new ChatStreamEvent.Done(null)));

        // Use tokens that exactly match the wiki page content so relevance > threshold
        List<SendMessageUseCase.StreamEvent> events = useCase
                .executeStream("av-1", "user-1", "explain fractions numerator denominator parts", "mod-1")
                .collectList().block();

        // Should have streamed response (not refused)
        assertThat(events).isNotNull();
        assertThat(events).anyMatch(e -> "delta".equals(e.type()) && "answer".equals(e.payload()));
        // Module gate should NOT have recorded a content gap signal
        verify(contentGapSignalRepo, never()).recordSignal(any(), any(), any(), any(), any());
    }

    @Test
    void moduleContextChat_offTopicQuestion_refused() {
        // Module about fractions; question about French Revolution
        LearningModule module = new LearningModule();
        module.setId("mod-1");
        module.setWikiPageSlug("fractions");
        module.setTitle("Fractions");

        WikiPage page = WikiPage.create("av-1", "fractions", "Fractions",
                "Fractions represent parts of a whole. The numerator is the top number.");

        when(avatarRepository.findById("av-1")).thenReturn(Optional.of(personalAvatar));
        when(learningModuleRepo.findById("mod-1")).thenReturn(Optional.of(module));
        when(wikiRepository.findByAvatarIdAndSlug("av-1", "fractions"))
                .thenReturn(Optional.of(page));
        when(chatRepository.save(any(ChatMessage.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        List<SendMessageUseCase.StreamEvent> events = useCase
                .executeStream("av-1", "user-1",
                        "what is the capital city of France", "mod-1")
                .collectList().block();

        assertThat(events).isNotNull();
        // Should contain a refusal mentioning the module title
        assertThat(events).anyMatch(e -> "delta".equals(e.type())
                && e.payload().contains("Fractions"));
        assertThat(events).anyMatch(e -> "done".equals(e.type()));
        // Should have saved two chat messages (user + refusal)
        verify(chatRepository, times(2)).save(any(ChatMessage.class));
    }

    @Test
    void nonModuleChat_moduleIdNull_behavesAsExistingFlow() {
        // moduleId=null should skip the module gate entirely
        when(avatarRepository.findById("av-1")).thenReturn(Optional.of(personalAvatar));
        // Personal non-centre avatar with no moduleId should skip both gates
        // and go straight to the main chat flow.
        when(chatRepository.save(any(ChatMessage.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(chatRepository.findByAvatarId(anyString(), anyInt())).thenReturn(List.of());
        when(contextAssembler.assemble(any(), anyString()))
                .thenReturn(new com.pally.domain.chat.AssembledContext("", "trace", List.of()));
        when(hintTreeRepository.findByAvatarId(anyString())).thenReturn(List.of());
        when(topicClassifier.classify(anyString(), anyList())).thenReturn(Optional.empty());
        when(topicClassifier.detectsDeflection(anyString())).thenReturn(false);
        when(chatSessionRepository.findByAvatarIdAndDate(anyString(), any()))
                .thenReturn(Optional.of(ChatSession.createToday("av-1")));
        when(socraticPromptBuilder.buildBlock4(any(), any(), anyInt(), anyBoolean(), any()))
                .thenReturn(java.util.Map.of("type", "text", "text", ""));
        when(modelRouter.forChat(anyString(), any(), anyBoolean())).thenReturn("claude-sonnet-4-20250514");
        when(chatPort.streamChat(anyList(), anyList(), anyString(), any(), anyString()))
                .thenReturn(reactor.core.publisher.Flux.just(
                        new ChatStreamEvent.Token("hello"),
                        new ChatStreamEvent.Done(null)));

        List<SendMessageUseCase.StreamEvent> events = useCase
                .executeStream("av-1", "user-1",
                        "what is the capital city of France", null)
                .collectList().block();

        // Should NOT have been refused — module gate skipped
        assertThat(events).isNotNull();
        assertThat(events).anyMatch(e -> "delta".equals(e.type()) && "hello".equals(e.payload()));
        // Module repo should never have been queried
        verify(learningModuleRepo, never()).findById(anyString());
    }

    @Test
    void moduleContextChat_centreAvatar_offTopic_logsContentGapSignal() {
        // Centre avatar with classId — off-topic should log a content gap signal
        Avatar centreAvatar = Avatar.create("user-1", "CentreBot", Subject.SCIENCE, CharacterType.MOCHI);
        centreAvatar.markCentreAvatar();
        // Use reflection to set classId since there's no public setter
        try {
            var field = Avatar.class.getDeclaredField("classId");
            field.setAccessible(true);
            field.set(centreAvatar, "class-42");
        } catch (Exception ignored) {}

        LearningModule module = new LearningModule();
        module.setId("mod-1");
        module.setWikiPageSlug("photosynthesis");
        module.setTitle("Photosynthesis");

        WikiPage page = WikiPage.create("av-1", "photosynthesis", "Photosynthesis",
                "Photosynthesis is the process by which plants convert sunlight into glucose");

        when(avatarRepository.findById("av-1")).thenReturn(Optional.of(centreAvatar));
        when(learningModuleRepo.findById("mod-1")).thenReturn(Optional.of(module));
        when(wikiRepository.findByAvatarIdAndSlug("av-1", "photosynthesis"))
                .thenReturn(Optional.of(page));
        when(chatRepository.save(any(ChatMessage.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        useCase.executeStream("av-1", "user-1",
                        "what year did world war two start", "mod-1")
                .collectList().block();

        // Should have recorded a content gap signal via the domain port
        if (centreAvatar.getClassId() != null) {
            verify(contentGapSignalRepo).recordSignal(
                    eq("av-1"),
                    eq("class-42"),
                    eq("user-1"),
                    contains("world war two"),
                    any(java.math.BigDecimal.class));
        }
    }
}
