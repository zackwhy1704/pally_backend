package com.pally.infrastructure.ai;

/**
 * The single source of the output-language instruction threaded into every generation
 * prompt (V124 content_language). This is deliberately ONE template + a language variable,
 * NOT per-language prompt forks.
 *
 * <p><b>Byte-identical-English invariant (load-bearing).</b> {@link #directive(String)} returns
 * the empty string for English (and for null/blank/unknown), so appending it to a prompt whose
 * content_language is {@code "en"} produces a byte-identical prompt string. The entire hardened
 * English pipeline must not move when a language variable is threaded through every prompt
 * builder; that guarantee is structural here (empty append), and it is pinned by a golden
 * guard test. Only a recognised non-English language yields a non-empty append.
 *
 * <p>Append the directive at the TAIL of a prompt (after the format/citation rules, before any
 * "Reply ONLY with JSON" line). Do NOT inject at the shared LLM client chokepoints
 * ({@code GeminiCompletionService}/{@code ClaudeApiClient}) — those also carry internal
 * classify/judge/router calls whose output MUST stay English/ASCII.
 */
public final class PromptLanguage {

    private PromptLanguage() {}

    /**
     * @param contentLanguage the avatar's content_language ("en" | "zh"); null/blank tolerated.
     * @return the output-language instruction to append, or "" for English / null / blank /
     *         any unrecognised language (fail to English — never emit a broken prompt).
     */
    public static String directive(String contentLanguage) {
        if (contentLanguage == null) return "";
        String lang = contentLanguage.trim().toLowerCase();
        if (lang.isEmpty() || lang.equals("en")) return "";
        if (lang.equals("zh")) return ZH_DIRECTIVE;
        return ""; // unknown language degrades to English rather than an unlocalised half-prompt
    }

    /** True when a non-empty directive would be appended (i.e. a non-English target). */
    public static boolean isTranslated(String contentLanguage) {
        return !directive(contentLanguage).isEmpty();
    }

    // NEEDS-NATIVE-REVIEW — machine-drafted Singapore Simplified-Chinese instruction. A native
    // SG educator MUST vet this before ship: it shapes EVERY zh artifact the AI generates, and a
    // 公交车-style mainlandism in a P3 华文 class is a first-contact credibility failure. The
    // instruction is written in English (the model reads it) and directs Chinese OUTPUT; it keeps
    // JSON keys / slugs / the SOURCE: citation marker in ASCII so the language-agnostic contracts
    // (parser, groundedness) still hold.
    private static final String ZH_DIRECTIVE =
            "\n\nRespond entirely in Simplified Chinese using Singapore conventions: "
            + "use 华语 (not 中文), 巴士 (not 公交车), and Hanyu Pinyin for any romanization; "
            + "never mix in Traditional characters. "
            + "Keep every JSON key, slug, enum value, and the literal \"SOURCE:\" citation marker "
            + "exactly as specified in English/ASCII — translate only the human-readable prose.";
}
