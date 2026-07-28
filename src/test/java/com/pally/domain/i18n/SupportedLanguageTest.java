package com.pally.domain.i18n;

import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The user-selectable-language contract for 1c: accept en|zh (case/whitespace-insensitive), reject
 * everything else with a 400 — never a silent default. A typo'd "zh-CN" must tell the caller, not
 * quietly serve English.
 */
class SupportedLanguageTest {

    @Test
    void acceptsEnAndZh_normalisingCaseAndWhitespace() {
        assertThat(SupportedLanguage.validate("en")).isEqualTo("en");
        assertThat(SupportedLanguage.validate("zh")).isEqualTo("zh");
        assertThat(SupportedLanguage.validate("EN")).isEqualTo("en");
        assertThat(SupportedLanguage.validate("  Zh ")).isEqualTo("zh");
    }

    @Test
    void rejectsUnsupportedWith400_notSilentDefault() {
        assertThatThrownBy(() -> SupportedLanguage.validate("zh-CN"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getHttpStatus()).isEqualTo(400));
        assertThatThrownBy(() -> SupportedLanguage.validate("fr"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> SupportedLanguage.validate("english"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsNullOrBlankWith400() {
        assertThatThrownBy(() -> SupportedLanguage.validate(null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> SupportedLanguage.validate("  ")).isInstanceOf(BusinessException.class);
    }
}
