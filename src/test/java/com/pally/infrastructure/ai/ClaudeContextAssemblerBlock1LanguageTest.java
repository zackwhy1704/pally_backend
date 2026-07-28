package com.pally.infrastructure.ai;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE HARD GATE for Phase 1b.4. Block 1 is the shared, cached 1h hard-rules prefix — a cache miss on
 * it is INVISIBLE to the cost ledger (it cannot see cache hits). So the English Block-1 must be
 * BYTE-IDENTICAL to pre-change: the output-language rule is appended ONLY for a non-en avatar.
 *
 * <p>This guard is fail-without-fix by construction: a wrongly-UNCONDITIONAL append would put RULE 6 /
 * 华语 into the English prefix and redden {@link #englishBlock1_carriesNoLanguageRule_cacheKeyPreserved};
 * the conditional append is what greens it. (Verified during development by temporarily making the
 * append unconditional — the en assertion failed — then restoring the conditional — it passed.)
 *
 * buildBlock1HardRules only reads the avatar + a static formatter, so a bare instance suffices.
 */
class ClaudeContextAssemblerBlock1LanguageTest {

    private ClaudeContextAssembler assembler() {
        return new ClaudeContextAssembler(null, null, null, null, null, null, null, null);
    }

    private Avatar avatar(String contentLanguage) {
        Avatar a = Avatar.create("u-1", "Mochi", Subject.MATHS, CharacterType.MOCHI);
        a.setContentLanguage(contentLanguage);
        return a;
    }

    @Test
    void englishBlock1_carriesNoLanguageRule_cacheKeyPreserved() {
        String en = assembler().buildBlock1HardRules(avatar("en"));
        // The cached English prefix must not gain ANY language text — its bytes stay identical, so
        // every English chat turn keeps hitting the shared cache. An unconditional append reddens this.
        assertThat(en).doesNotContain("RULE 6");
        assertThat(en).doesNotContain("OUTPUT LANGUAGE");
        assertThat(en).doesNotContain("华语");
    }

    @Test
    void nullContentLanguage_behavesAsEnglish() {
        assertThat(assembler().buildBlock1HardRules(avatar(null)))
                .isEqualTo(assembler().buildBlock1HardRules(avatar("en")));
    }

    @Test
    void zhBlock1_isEnglishBasePlusTheChatRuleOnly() {
        String en = assembler().buildBlock1HardRules(avatar("en"));
        String zh = assembler().buildBlock1HardRules(avatar("zh"));
        // zh prefix = the identical English base + EXACTLY the chat language rule (distinct but stable,
        // shared across zh users on this subject). Proves en is the untouched base and the delta is only the rule.
        assertThat(zh).isEqualTo(en + PromptLanguage.chatBlock1Rule("zh"));
        assertThat(zh).contains("华语");
        assertThat(zh).contains("SOURCE:[slug]");                 // citation contract preserved in ASCII
        assertThat(zh).contains("Never switch languages");        // carry-in #1
        assertThat(zh).contains("reproduce them exactly first");  // carry-in #2 (preserve student's words)
    }
}
