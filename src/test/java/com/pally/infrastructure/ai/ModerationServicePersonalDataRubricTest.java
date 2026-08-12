package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.infrastructure.persistence.safety.ChatSafetyFlagJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Pins DEFERRED.md "Context-blind moderation false-positives on material-grounded
 * comprehension questions" — the PERSONAL_DATA rubric was clarified so a
 * comprehension question about a THIRD PARTY in uploaded study material ("what bus
 * does the character take?") no longer reads as the CHILD disclosing their own info.
 *
 * <p>Measured empirically against the REAL classifier (not mocked) before and after
 * this change, using a 16-case labelled fixture set (10 material-comprehension
 * questions incl. zh cases from the actual p3_huawen_wo_de_linli.pdf fixture, 4
 * genuine self-disclosure examples, 2 SELF_HARM controls), run 3x for stability:
 *   baseline: 2/10 comprehension questions false-positive-blocked, 4/4 disclosures
 *             correctly blocked, 2/2 self-harm correctly blocked.
 *   fixed:    0/10 false positives (stable across 3 runs), 4/4 disclosures STILL
 *             correctly blocked, 2/2 self-harm STILL correctly blocked.
 *
 * <p>This test only pins the STATIC properties that don't require a live API call:
 * the clarified wording actually reaches the prompt, and the SELF_HARM keyword
 * fallback (used when the classifier itself is unreachable) is untouched by this
 * change. The empirical before/after comparison above is not re-run in CI — it
 * requires the real Claude API — see DEFERRED.md for the full measurement record.
 */
@ExtendWith(MockitoExtension.class)
class ModerationServicePersonalDataRubricTest {

    @Mock ClaudeApiClient claude;
    @Mock ModelRouter modelRouter;
    @Mock ChatSafetyFlagJpaRepository flagRepo;
    @Mock SafetyAlertService alerts;

    ModerationService svc;

    @BeforeEach
    void setUp() throws Exception {
        svc = new ModerationService(claude, new ObjectMapper(), modelRouter, flagRepo, alerts);
        lenient().when(modelRouter.getHaikuModel()).thenReturn("haiku");
        lenient().when(claude.completeFast(any(), anyInt(), anyString(), anyString()))
                .thenReturn("{\"category\":\"SAFE\",\"severity\":\"SAFE\"}");
    }

    @Test
    void prompt_clarifiesPersonalDataIsAboutTheChildsOwnInfoNotStudyMaterial() throws Exception {
        svc.screenInput("u", "av", "m", "他每天乘搭几号巴士上学？车程多久？", "zh");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(claude)
                .completeFast(any(), anyInt(), promptCaptor.capture(), anyString());
        String prompt = promptCaptor.getValue();

        assertThat(prompt)
                .as("the rubric must explicitly distinguish the child's OWN info from study-material content")
                .contains("THEIR OWN")
                .contains("study material")
                .contains("comprehension, not disclosure");
    }

    @Test
    void selfHarmKeywordFallback_untouchedByThePersonalDataRubricChange() throws Exception {
        // Classifier unreachable -> falls back to the keyword check, which this
        // change does not touch. Must still hard-block.
        when(claude.completeFast(any(), anyInt(), anyString(), anyString()))
                .thenThrow(new RuntimeException("classifier unavailable"));

        var r = svc.screenInput("u", "av", "m", "I want to end my life", "en");

        assertThat(r.flagged()).isTrue();
        assertThat(r.category()).isEqualTo("SELF_HARM");
        assertThat(r.isHighSeverity()).isTrue();
    }
}
