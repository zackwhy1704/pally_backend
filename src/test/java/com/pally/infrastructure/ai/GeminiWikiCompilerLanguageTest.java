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
 * The KEYSTONE guard for Phase 1b: threading content_language must leave the English compile
 * prompt BYTE-IDENTICAL. buildPrompt only reads the avatar/files/pages, so a bare instance
 * (null collaborators) exercises the prompt assembly purely.
 *
 * <p>The assertion {@code zh == en + directive("zh")} proves BOTH halves at once: the English
 * prompt has nothing extra appended (byte-identical base), and the ONLY difference for a zh
 * avatar is exactly the Singapore-conventions directive. Fails without the append; fails if the
 * English branch ever gains its own language text.
 */
class GeminiWikiCompilerLanguageTest {

    private GeminiWikiCompiler compiler() {
        return new GeminiWikiCompiler(null, null, null, null, null, null);
    }

    private Avatar avatar(String contentLanguage) {
        Avatar a = Avatar.create("u-1", "Notes", Subject.SCIENCE, CharacterType.MOCHI);
        a.setContentLanguage(contentLanguage);
        return a;
    }

    @Test
    void englishCompilePrompt_isByteIdenticalBase_zhIsBasePlusDirectiveOnly() {
        GeminiWikiCompiler c = compiler();
        String en = c.buildPrompt(avatar("en"), List.<KnowledgeFile>of(), List.<WikiPage>of());
        String zh = c.buildPrompt(avatar("zh"), List.<KnowledgeFile>of(), List.<WikiPage>of());

        // English prompt carries NO language directive → the hardened English pipeline is unmoved.
        assertThat(en).doesNotContain("华语");
        // zh is the identical English base plus EXACTLY the appended directive — nothing else moved.
        assertThat(zh).isEqualTo(en + PromptLanguage.directive("zh"));
        assertThat(zh).contains("华语");
    }

    @Test
    void nullContentLanguage_behavesAsEnglish() {
        GeminiWikiCompiler c = compiler();
        String nul = c.buildPrompt(avatar(null), List.<KnowledgeFile>of(), List.<WikiPage>of());
        String en = c.buildPrompt(avatar("en"), List.<KnowledgeFile>of(), List.<WikiPage>of());
        assertThat(nul).isEqualTo(en);
        assertThat(nul).doesNotContain("华语");
    }
}
