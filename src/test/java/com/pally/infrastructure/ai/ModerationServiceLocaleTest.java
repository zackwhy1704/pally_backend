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

    /**
     * Companion to the reply-language tests above, but for the BLOCK DECISION
     * itself (flagged + isHighSeverity), not the reply text — this is what
     * every caller that discards safeReply (e.g. StudyGroupService's
     * createGroup/report, which have no content-language signal and pass a
     * hardcoded "en") actually depends on. Proves the classifier's decision
     * on real zh content is unaffected by a WRONG declared contentLanguage —
     * so a hardcoded "en" there is a harmless placeholder, not an accuracy
     * gap, because contentLanguage never reaches the classification prompt
     * (only buildSafeReply reads it — see source).
     */
    @Test
    void highSeverityBlockDecisionOnZhContent_unaffectedByWrongDeclaredLanguage() throws Exception {
        // A realistic classifier response for actual zh bullying content —
        // Claude reads the raw text regardless of what contentLanguage claims.
        when(claude.completeFast(any(), anyInt(), anyString(), anyString()))
                .thenReturn("{\"category\":\"BULLYING\",\"severity\":\"HIGH\"}");

        // Declare the WRONG language for zh text — mirrors the hardcoded "en"
        // at StudyGroupService.createGroup()/report(), which have no wiki
        // page (unlike shareNote()) to source a real content-language from.
        var r = svc.screenInput("u", "av", "m", "你这个废物，去死吧", "en");

        assertThat(r.flagged()).isTrue();
        assertThat(r.isHighSeverity()).isTrue();
        assertThat(r.category()).isEqualTo("BULLYING");
    }
}
