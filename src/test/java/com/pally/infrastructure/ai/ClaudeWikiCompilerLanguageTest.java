package com.pally.infrastructure.ai;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.WikiPage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ClaudeWikiCompiler's own byte-identical-en guard — this compiler had none before this test
 * (a gap independent of, but adjacent to, the mixed-language bug: see
 * GeminiWikiCompilerLanguageTest, which this mirrors). ClaudeWikiCompiler is Tier 3 of the
 * compile chain (fires on Gemini error/cooldown, a real production path) and threads
 * content_language the same way GeminiWikiCompiler does, so it needs the same two guarantees:
 * the English prompt stays byte-identical, and the few-shot example carries no concrete
 * English prose strong enough to outweigh the trailing zh directive.
 */
class ClaudeWikiCompilerLanguageTest {

    private ClaudeWikiCompiler compiler() {
        return new ClaudeWikiCompiler(null, null, null);
    }

    private Avatar avatar(String contentLanguage) {
        Avatar a = Avatar.create("u-1", "Notes", Subject.SCIENCE, CharacterType.MOCHI);
        a.setContentLanguage(contentLanguage);
        return a;
    }

    @Test
    void englishCompilePrompt_isByteIdenticalBase_zhIsBasePlusDirectiveOnly() {
        ClaudeWikiCompiler c = compiler();
        String en = c.buildPrompt(avatar("en"), List.<KnowledgeFile>of(), List.<WikiPage>of(), null);
        String zh = c.buildPrompt(avatar("zh"), List.<KnowledgeFile>of(), List.<WikiPage>of(), null);

        // English prompt carries NO language directive → the hardened English pipeline is unmoved.
        assertThat(en).doesNotContain("华语");
        // zh is the identical English base plus EXACTLY the appended directive — nothing else moved.
        assertThat(zh).isEqualTo(en + PromptLanguage.directive("zh"));
        assertThat(zh).contains("华语");
    }

    @Test
    void nullContentLanguage_behavesAsEnglish() {
        ClaudeWikiCompiler c = compiler();
        String nul = c.buildPrompt(avatar(null), List.<KnowledgeFile>of(), List.<WikiPage>of(), null);
        String en = c.buildPrompt(avatar("en"), List.<KnowledgeFile>of(), List.<WikiPage>of(), null);
        assertThat(nul).isEqualTo(en);
        assertThat(nul).doesNotContain("华语");
    }

    /**
     * Root cause of the mixed en/zh compile output on Tier 3: TWO concrete English worked
     * examples ("Boiling Point of Water...", "Ohm's Law: V = IR...") sat immediately before
     * the trailing zh directive — a stronger in-context signal than either. The header can't
     * be made language-conditional without breaking
     * {@link #englishCompilePrompt_isByteIdenticalBase_zhIsBasePlusDirectiveOnly()}'s
     * exact-equality guard, so the fix is a NEUTRAL example — structure/placeholders only,
     * for either language — same approach as GeminiWikiCompiler.
     */
    @Test
    void examplePromptTemplate_carriesNoConcreteEnglishProse_forEitherLanguage() {
        ClaudeWikiCompiler c = compiler();
        String en = c.buildPrompt(avatar("en"), List.<KnowledgeFile>of(), List.<WikiPage>of(), null);
        String zh = c.buildPrompt(avatar("zh"), List.<KnowledgeFile>of(), List.<WikiPage>of(), null);

        for (String prompt : List.of(en, zh)) {
            assertThat(prompt)
                    .as("the old worked examples' concrete English sentences must be gone")
                    .doesNotContain("Boiling is when water turns from")
                    .doesNotContain("Water boils at")
                    .doesNotContain("Boiling Point of Water")
                    .doesNotContain("Ohm's Law")
                    .doesNotContain("voltage (volts), I is current");
        }
    }
}
