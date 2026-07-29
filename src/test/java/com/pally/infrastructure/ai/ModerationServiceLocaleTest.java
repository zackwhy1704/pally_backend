package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.infrastructure.persistence.safety.ChatSafetyFlagJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The child-safe refusal reply must follow the avatar's content language — a zh
 * session must never get an English refusal (the leak found in the zh E2E). This
 * pins the reply language WITHOUT touching the classifier's OFF_TOPIC decision
 * (the false-positive is a separate, ledgered problem).
 */
@ExtendWith(MockitoExtension.class)
class ModerationServiceLocaleTest {

    @Mock ClaudeApiClient claude;
    @Mock ModelRouter modelRouter;
    @Mock ChatSafetyFlagJpaRepository flagRepo;
    @Mock SafetyAlertService alerts;

    ModerationService svc;

    @BeforeEach
    void setUp() throws Exception {
        svc = new ModerationService(claude, new ObjectMapper(), modelRouter, flagRepo, alerts);
        lenient().when(modelRouter.getHaikuModel()).thenReturn("haiku");
        // Classifier flags the message OFF_TOPIC — the decision under test is only
        // the REPLY LANGUAGE, not whether the flag was correct.
        when(claude.completeFast(any(), anyInt(), anyString(), anyString()))
                .thenReturn("{\"category\":\"OFF_TOPIC\",\"severity\":\"LOW\"}");
    }

    @Test
    void offTopicReplyFollowsZhContentLanguage() {
        var r = svc.screenInput("u", "av", "m", "小峰乘搭几号巴士？", "zh");
        assertThat(r.flagged()).isTrue();
        assertThat(r.safeReply())
                .as("zh session gets a Chinese refusal")
                .contains("学习")
                .doesNotContain("grown-up");
    }

    @Test
    void offTopicReplyStaysEnglishForEn() {
        var r = svc.screenInput("u", "av", "m", "what's the weather?", "en");
        assertThat(r.safeReply()).contains("grown-up");
        assertThat(r.safeReply()).doesNotContain("学习");
    }

    @Test
    void unknownOrNullLanguageDegradesToEnglish() {
        var r = svc.screenInput("u", "av", "m", "off topic", null);
        assertThat(r.safeReply()).contains("grown-up");
    }
}
