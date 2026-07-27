package com.pally.domain.flag;

import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.config.AdminEmailService;
import com.pally.infrastructure.persistence.flag.UserFeatureFlagJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeatureFlagServiceTest {

    @Mock UserFeatureFlagJpaRepository flagRepo;
    @Mock UserRepository userRepository;
    @Mock AdminEmailService adminEmailService;

    private FeatureFlagService withVoiceEnv(boolean value) {
        var svc = new FeatureFlagService(flagRepo, userRepository, adminEmailService);
        ReflectionTestUtils.setField(svc, "voiceInputEnabled", value);
        when(flagRepo.findByUserId("u1")).thenReturn(List.of());
        when(userRepository.findById("u1")).thenReturn(Optional.empty());
        return svc;
    }

    @Test
    void getFlags_returnsVoiceInputTrue_whenEnvEnabled() {
        // The global Railway VOICE_INPUT_ENABLED kill-switch reaches every user as
        // the voice_input flag. Fail-without-fix: without the getFlags wiring, the key
        // is absent and the client's fail-closed reader keeps the mic dark forever.
        assertThat(withVoiceEnv(true).getFlags("u1")).containsEntry("voice_input", true);
    }

    @Test
    void getFlags_returnsVoiceInputFalse_whenEnvDisabled() {
        assertThat(withVoiceEnv(false).getFlags("u1")).containsEntry("voice_input", false);
    }
}
