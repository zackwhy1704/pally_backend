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

    /**
     * Chat-specific output-language rule appended to the CACHED Block-1 hard-rules prefix.
     *
     * <p>Returns "" for 'en' (and null/blank/unknown) so the English Block-1 is BYTE-IDENTICAL: the
     * shared 1h cache prefix is unchanged, so every English chat turn keeps hitting cache. A change
     * here would be INVISIBLE to the cost ledger (it cannot see cache hits) — hence the append MUST
     * stay conditional, pinned by a fail-without-fix byte-identical guard. A 'zh' avatar gets a
     * distinct but stable prefix (shared across zh users on the same subject; larger, so it still
     * clears the cache-write token threshold).
     *
     * <p>This is a CHAT rule, NOT the JSON-oriented generation {@link #directive}: it drops the
     * schema clauses, preserves the literal SOURCE:[slug] citation marker in ASCII, and folds in the
     * conversational carry-ins (stay in the language; reproduce the student's own words verbatim).
     */
    public static String chatBlock1Rule(String contentLanguage) {
        if (contentLanguage == null) return "";
        String lang = contentLanguage.trim().toLowerCase();
        if (lang.isEmpty() || lang.equals("en")) return "";
        if (lang.equals("zh")) return ZH_CHAT_BLOCK1_RULE;
        return "";
    }

    // Operator-provided Singapore Simplified-Chinese instruction (replaced the initial machine
    // draft). It shapes EVERY zh artifact the AI generates, so a final native-SG-educator sign-off
    // on the SG-specific vocabulary (德士/组屋/HDB, 华语-not-中文) is still wise before wide rollout.
    // Written in English (the model reads it) and directs Chinese OUTPUT; it keeps JSON keys / slugs
    // / the SOURCE: citation marker in ASCII so the language-agnostic contracts (parser,
    // groundedness) still hold. NB: this is appended to ONE-SHOT generation prompts with strict JSON
    // schemas — deliberately excludes interactive-tutor clauses (bilingual-on-request, "define +
    // example + common mistake" scaffolds) that would fight those fixed output contracts.
    // NEEDS-NATIVE-REVIEW — chat companion to ZH_DIRECTIVE (a live tutor dialogue, not one-shot JSON).
    // Appended CONDITIONALLY to the cached chat Block-1; keeps SOURCE:[slug] in ASCII and folds in the
    // two conversational carry-ins (stay in the language; reproduce the student's words verbatim).
    private static final String ZH_CHAT_BLOCK1_RULE =
            "\nRULE 6 — OUTPUT LANGUAGE:\n"
          + "Respond in Simplified Chinese using Singapore conventions (华语 not 中文, 巴士 not 公交车, "
          + "Hanyu Pinyin for romanization; never Traditional characters). "
          + "Keep the literal SOURCE:[slug] citation marker and all page slugs in English/ASCII. "
          + "Always refer to yourself and the study buddy as 小伴 (the mascot's official Chinese name), "
          + "never \"Mochi\"; this OVERRIDES keeping brand names in English for the mascot specifically. "
          + "Never switch languages unexpectedly — stay in Chinese unless the student explicitly asks for a "
          + "translation or a language comparison. "
          + "When quoting or correcting the student's own words, reproduce them exactly first, then respond.\n";

    private static final String ZH_DIRECTIVE =
            "\n\nRespond entirely in Simplified Chinese (简体中文) using Singapore Mandarin conventions. "
          + "Use 华语 (never 中文 when referring to the language), 巴士 (not 公交车), 德士 (not 出租车), "
          + "组屋 (not 小区/住宅区 where HDB is intended), and 手机号码 (not 手机号). "
          + "Prefer Singapore/Malaysia vocabulary where appropriate while remaining easily understood by all Mandarin speakers. "
          + "Never output Traditional Chinese characters unless they appear inside quoted source material, proper names, code, or user input. "
          + "Use standard Hanyu Pinyin with tone marks (e.g. nǐ hǎo, xuéshēng) whenever romanization is required; "
          + "never use Wade-Giles, Zhuyin, Tongyong Pinyin, or numbered tones unless explicitly requested. "
          + "For educational content, use clear, natural, age-appropriate language, defining uncommon vocabulary the first time it appears. "
          + "When introducing a term that GENUINELY ORIGINATES IN ENGLISH — a specialised technical, scientific, "
          + "or loaned term with no universally-known Chinese equivalent — give the Chinese term first followed by "
          + "the English term in parentheses on first mention only (e.g. 机器学习 (Machine Learning)); thereafter "
          + "use the Chinese term consistently unless the English term is required. Do NOT add an English "
          + "parenthetical for ordinary vocabulary that is already native Chinese in the source material — everyday "
          + "nouns, civic or cultural terms (e.g. 组屋, 乐龄人士, 礼让) are not \"technical terms\" for this rule and "
          + "must appear in Chinese alone, with no English gloss. "
          + "Preserve all mathematical notation, formulas, chemical symbols, programming code, JSON, XML, HTML, Markdown, URLs, email addresses, "
          + "file paths, API names, class names, function names, variable names, enum values, identifiers, slugs, placeholders, and other machine-readable text exactly as provided. "
          + "Keep every JSON key, slug, enum value, placeholder token, template variable, and the literal \"SOURCE:\" citation marker "
          + "exactly in English/ASCII — translate only human-readable prose. "
          + "Do not translate code comments if they are explicitly marked to remain in English. "
          + "Maintain the original structure, numbering, bullet lists, tables, whitespace-sensitive formatting, and Markdown unless instructed otherwise. "
          + "Keep brand names, product names, company names, proper nouns, and official titles in their official form unless a widely accepted Simplified Chinese name exists. "
          // Mascot naming: SUPERSEDES the brand-name rule immediately above for the mascot
          // specifically, so the model is not left choosing between two rules. The app UI names
          // the mascot 小伴; generated content must match or a compiled lesson says "Mochi" while
          // the app says 小伴 (a split-brain a teacher notices).
          + "EXCEPTION to the brand-name rule above, which it OVERRIDES for the mascot only: the study "
          + "companion / mascot's official Simplified Chinese name is 小伴. Always call the mascot 小伴 in "
          + "Chinese prose and never write \"Mochi\"; treat 小伴 as the mascot's official Chinese form. "
          + "Use full-width Chinese punctuation (，。！？；：（）《》【】) in prose, but preserve ASCII punctuation inside code, JSON, Markdown syntax, URLs, and other machine-readable text. "
          + "Avoid mixing English into normal prose except for established technical terminology, proper nouns, or when explicitly requested. "
          + "If the user requests bilingual output, present Chinese first and English second unless instructed otherwise. "
          // One-shot-safe rules folded in from the operator directive (the interactive-tutor and
          // schema-conflicting clauses were deliberately left for the chat system prompt / omitted).
          + "Be consistent: once a translation for a technical term is introduced, reuse it throughout. "
          + "Prefer simpler vocabulary for less-advanced learners, but do not oversimplify technical concepts. "
          + "Avoid Cantonese, Taiwanese-Mandarin expressions, and Mainland-only slang unless they are explicitly the subject. "
          + "Do not append Pinyin to every sentence — use it only for new or difficult vocabulary, not routine text.";
}
