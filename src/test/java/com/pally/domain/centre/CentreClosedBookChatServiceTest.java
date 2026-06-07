package com.pally.domain.centre;

import com.pally.infrastructure.ai.ClaudeApiClient;
import com.pally.infrastructure.persistence.avatar.AvatarJpaEntity;
import com.pally.infrastructure.persistence.avatar.AvatarJpaRepository;
import com.pally.infrastructure.persistence.centre.CentreChatMessageJpaRepository;
import com.pally.infrastructure.persistence.centre.CentreClassJpaEntity;
import com.pally.infrastructure.persistence.centre.CentreClassJpaRepository;
import com.pally.infrastructure.persistence.centre.CentreEnrolmentJpaEntity;
import com.pally.infrastructure.persistence.centre.CentreEnrolmentJpaRepository;
import com.pally.infrastructure.persistence.centre.WikiChunkJpaEntity;
import com.pally.infrastructure.persistence.centre.WikiChunkJpaRepository;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CentreClosedBookChatServiceTest {

    @Mock CentreEnrolmentJpaRepository enrolmentRepo;
    @Mock CentreClassJpaRepository classRepo;
    @Mock WikiChunkJpaRepository chunkRepo;
    @Mock CentreChatMessageJpaRepository chatRepo;
    @Mock AvatarJpaRepository avatarRepo;
    @Mock ClaudeApiClient claudeApiClient;

    @InjectMocks CentreClosedBookChatService service;

    private static final String USER_ID = "user-1";
    private static final String CENTRE_ID = "centre-1";
    private static final String CLASS_ID = "class-1";

    private CentreEnrolmentJpaEntity activeEnrolment;
    private CentreClassJpaEntity cls;

    @BeforeEach
    void setUp() {
        activeEnrolment = new CentreEnrolmentJpaEntity();
        activeEnrolment.setId("enrolment-1");
        activeEnrolment.setCentreId(CENTRE_ID);
        activeEnrolment.setClassId(CLASS_ID);
        activeEnrolment.setUserId(USER_ID);
        activeEnrolment.setStatus("ACTIVE");
        activeEnrolment.setAvatarId("avatar-1");
        activeEnrolment.setJoinedAt(Instant.now());

        cls = new CentreClassJpaEntity();
        cls.setId(CLASS_ID);
        cls.setCentreId(CENTRE_ID);
        cls.setName("Sec 3 Maths");
        cls.setCreatedAt(Instant.now());

        AvatarJpaEntity avatar = new AvatarJpaEntity();
        avatar.setId("avatar-1");
        avatar.setAvatarLocked(false);

        lenient().when(enrolmentRepo.findByClassIdAndUserId(CLASS_ID, USER_ID))
                .thenReturn(Optional.of(activeEnrolment));
        lenient().when(avatarRepo.findById("avatar-1")).thenReturn(Optional.of(avatar));
        lenient().when(chatRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void sendMessage_noMatchingChunks_returnsTeacherRedirectWithoutLLMCall() {
        // No chunks at all → immediate redirect
        when(chunkRepo.findByCentreIdOrderByOrdering(CENTRE_ID)).thenReturn(List.of());

        List<String> events = service.sendMessage(CENTRE_ID, CLASS_ID, USER_ID, "What is quantum physics?")
                .collectList().block();

        assertThat(events).anyMatch(e -> e.contains("That's one for your teacher!"));
        // Claude must NOT be called when no chunks match
        verify(claudeApiClient, never()).streamResponseWithCache(any(), any(), any(int.class));
    }

    @Test
    void sendMessage_chunksBelowThreshold_returnsTeacherRedirectWithoutLLMCall() {
        // Chunk with unrelated content → score below 0.3
        WikiChunkJpaEntity chunk = makeChunk("The speed of light is 299792458 m/s");
        when(chunkRepo.findByCentreIdOrderByOrdering(CENTRE_ID)).thenReturn(List.of(chunk));

        List<String> events = service.sendMessage(CENTRE_ID, CLASS_ID, USER_ID, "What is photosynthesis in plants?")
                .collectList().block();

        assertThat(events).anyMatch(e -> e.contains("That's one for your teacher!"));
        verify(claudeApiClient, never()).streamResponseWithCache(any(), any(), any(int.class));
    }

    @Test
    void sendMessage_matchingChunks_callsLLMWithClosedBookPrompt() {
        // Chunk + query designed so keyword score = 3/4 = 0.75 > 0.3 threshold.
        // Query tokens (>2 chars): photosynthesis, plants, process, explain
        // Chunk tokens containing those: photosynthesis, plants, process ✓
        WikiChunkJpaEntity chunk = makeChunk(
                "Photosynthesis process allows plants to convert light energy into glucose.");
        when(chunkRepo.findByCentreIdOrderByOrdering(CENTRE_ID)).thenReturn(List.of(chunk));
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(cls));
        when(claudeApiClient.streamResponseWithCache(any(), any(), any(int.class)))
                .thenReturn(Flux.just(
                        "event: token\n",
                        "data: Photosynthesis is how plants make food!\n\n",
                        "event: done\n",
                        "data: \n\n"
                ));

        List<String> events = service.sendMessage(CENTRE_ID, CLASS_ID, USER_ID,
                "explain photosynthesis plants process").collectList().block();

        verify(claudeApiClient).streamResponseWithCache(
                argThat(blocks -> {
                    if (blocks.isEmpty()) return false;
                    String text = blocks.get(0).get("text").toString();
                    return text.contains("ONLY answer from the knowledge base")
                            && text.contains("Photosynthesis");
                }),
                any(),
                any(int.class)
        );
    }

    @Test
    void sendMessage_notEnrolled_throws403() {
        when(enrolmentRepo.findByClassIdAndUserId(CLASS_ID, "other-user"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendMessage(CENTRE_ID, CLASS_ID, "other-user", "hello"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not enrolled");
    }

    @Test
    void sendMessage_lockedAvatar_throws403() {
        AvatarJpaEntity lockedAvatar = new AvatarJpaEntity();
        lockedAvatar.setId("avatar-1");
        lockedAvatar.setAvatarLocked(true);
        when(avatarRepo.findById("avatar-1")).thenReturn(Optional.of(lockedAvatar));

        assertThatThrownBy(() -> service.sendMessage(CENTRE_ID, CLASS_ID, USER_ID, "hello"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("plan has ended");
    }

    // ── helpers ──────────────────────────────────────────────────────

    private WikiChunkJpaEntity makeChunk(String text) {
        WikiChunkJpaEntity c = new WikiChunkJpaEntity();
        c.setId("chunk-" + text.hashCode());
        c.setCentreId(CENTRE_ID);
        c.setTopicId("topic-1");
        c.setText(text);
        return c;
    }

    // Custom ArgumentMatcher import for argument verification
    private static <T> T argThat(java.util.function.Predicate<T> predicate) {
        return org.mockito.ArgumentMatchers.argThat(predicate::test);
    }
}
