package com.pally.api.progress;

import com.pally.domain.progress.ProgressService;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/// Unit tests for {@link ProgressService#levelRoadmap} — proves the reward
/// labels resolve per the caller's preferredLocale (added alongside the
/// achievements localization pass) without touching the unlocked/level shape.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProgressServiceLevelRoadmapTest {

    @Mock UserJpaRepository userRepo;

    @InjectMocks ProgressService service;

    private static final String USER = "user-1";

    private UserJpaEntity userAt(int level, String locale) {
        var u = new UserJpaEntity();
        u.setLevel(level);
        if (locale != null) u.setPreferredLocale(locale);
        return u;
    }

    @Test
    void unknownUser_throws404() {
        when(userRepo.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.levelRoadmap("ghost"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void defaultLocale_labelsAreEnglish() {
        when(userRepo.findById(USER)).thenReturn(Optional.of(userAt(1, null)));

        var body = service.levelRoadmap(USER);
        @SuppressWarnings("unchecked")
        var rewards = (List<Map<String, Object>>) body.get("rewards");

        var l2 = rewards.stream()
                .filter(r -> Integer.valueOf(2).equals(r.get("level")))
                .findFirst().orElseThrow();
        assertThat(l2.get("label")).isEqualTo("New Mochi colour");
    }

    @Test
    void zhLocale_labelsAreChinese() {
        when(userRepo.findById(USER)).thenReturn(Optional.of(userAt(1, "zh")));

        var body = service.levelRoadmap(USER);
        @SuppressWarnings("unchecked")
        var rewards = (List<Map<String, Object>>) body.get("rewards");

        var l2 = rewards.stream()
                .filter(r -> Integer.valueOf(2).equals(r.get("level")))
                .findFirst().orElseThrow();
        assertThat((String) l2.get("label")).isNotEqualTo("New Mochi colour").isNotBlank();
    }

    @Test
    void unlockedFlag_unaffectedByLocale() {
        when(userRepo.findById(USER)).thenReturn(Optional.of(userAt(10, "zh")));

        var body = service.levelRoadmap(USER);
        @SuppressWarnings("unchecked")
        var rewards = (List<Map<String, Object>>) body.get("rewards");

        var l2 = rewards.stream()
                .filter(r -> Integer.valueOf(2).equals(r.get("level")))
                .findFirst().orElseThrow();
        var l25 = rewards.stream()
                .filter(r -> Integer.valueOf(25).equals(r.get("level")))
                .findFirst().orElseThrow();
        assertThat(l2.get("unlocked")).isEqualTo(true);
        assertThat(l25.get("unlocked")).isEqualTo(false);
    }
}
