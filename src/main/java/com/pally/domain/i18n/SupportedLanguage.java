package com.pally.domain.i18n;

import com.pally.shared.exception.BusinessException;

import java.util.Set;

/**
 * The languages the app supports as a user-selectable value: user.preferred_locale (UI chrome) and
 * avatar.content_language (AI generation). Validation is EXPLICIT — an unsupported value (e.g. a
 * typo'd "zh-CN") is rejected with a 400, never silently defaulted to English, so the caller learns
 * their input was wrong instead of quietly getting the wrong language.
 */
public final class SupportedLanguage {

    private SupportedLanguage() {}

    public static final Set<String> SUPPORTED = Set.of("en", "zh");

    /**
     * @return the normalised (trimmed, lower-cased) code if supported.
     * @throws BusinessException 400 if null/blank or not one of {@link #SUPPORTED}.
     */
    public static String validate(String code) {
        if (code == null || code.isBlank()) {
            throw new BusinessException("Language is required (en or zh).", 400);
        }
        String normalized = code.trim().toLowerCase();
        if (!SUPPORTED.contains(normalized)) {
            throw new BusinessException(
                    "Unsupported language '" + code + "' — must be 'en' or 'zh'.", 400);
        }
        return normalized;
    }

    /**
     * Picks between a pre-translated en/zh pair for a STATIC data catalog
     * (achievement/reward copy) — distinct from {@code PromptLanguage}, which
     * appends a directive to an LLM prompt. Here there is no "base +
     * delta"; en and zh are two independent, fully-authored strings, so any
     * value other than exactly "zh" (including null/blank/unknown) resolves
     * to en — never a partial or malformed locale silently losing content.
     */
    public static String resolve(String en, String zh, String locale) {
        return "zh".equals(locale) ? zh : en;
    }
}
