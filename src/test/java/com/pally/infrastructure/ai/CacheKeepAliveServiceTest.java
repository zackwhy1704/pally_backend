package com.pally.infrastructure.ai;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.cost.AiUsageMeter;
import com.pally.domain.knowledge.WikiRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class CacheKeepAliveServiceTest {

    @Mock ClaudeApiClient claudeClient;
    @Mock ClaudeContextAssembler assembler;
    @Mock AvatarRepository avatarRepo;
    @Mock WikiRepository wikiRepo;
    @Mock ModelRouter modelRouter;
    @Mock AiUsageMeter aiUsageMeter;

    private CacheKeepAliveService svc() {
        return new CacheKeepAliveService(
                claudeClient, assembler, avatarRepo, wikiRepo, modelRouter, aiUsageMeter);
    }

    @Test
    void isIdle_atOrPastWindow_true_withinWindow_false_nullNeverIdle() {
        Instant now = Instant.parse("2026-07-23T00:00:00Z");
        // >= 15 min with no chat turn → idle (the ping loop must self-terminate).
        assertThat(CacheKeepAliveService.isIdle(now.minus(Duration.ofMinutes(16)), now)).isTrue();
        assertThat(CacheKeepAliveService.isIdle(now.minus(Duration.ofMinutes(15)), now)).isTrue();
        // Still active within the window → keep pinging.
        assertThat(CacheKeepAliveService.isIdle(now.minus(Duration.ofMinutes(10)), now)).isFalse();
        // Never had activity recorded → never treated as idle (avoids killing a fresh session).
        assertThat(CacheKeepAliveService.isIdle(null, now)).isFalse();
    }

    @Test
    void recordActivity_onInactiveAvatar_neverCreatesAPingLoop() {
        // Touch-only invariant: a chat turn resets an ACTIVE keepalive's idle timer but must
        // NEVER spin one up — chat-open (startKeepalive) owns creation. This is the guard that
        // keeps the leak fixed: no code path other than chat-open can start a paid loop.
        CacheKeepAliveService svc = svc();

        svc.recordActivity("av-never-opened");

        assertThat(svc.isActive("av-never-opened")).isFalse();
    }

    @Test
    void stopKeepalive_onInactiveAvatar_isNoOp_andStaysInactive() {
        CacheKeepAliveService svc = svc();

        svc.stopKeepalive("av-x");

        assertThat(svc.isActive("av-x")).isFalse();
    }
}
