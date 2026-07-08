package com.pally.infrastructure.auth;

import com.pally.infrastructure.persistence.auth.BiometricChallengeJpaRepository;
import com.pally.infrastructure.persistence.auth.BiometricRegistrationJpaEntity;
import com.pally.infrastructure.persistence.auth.BiometricRegistrationJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The security fix for the biometric auth-BYPASS: /verify must NEVER mint a token
 * from just (userId, deviceId) — it requires the device secret issued at register.
 */
@ExtendWith(MockitoExtension.class)
class BiometricAuthServiceTest {

    @Mock BiometricChallengeJpaRepository challengeRepo;
    @Mock BiometricRegistrationJpaRepository registrationRepo;
    @Mock UserJpaRepository userRepo;
    @Mock JwtService jwtService;

    BiometricAuthService svc;

    @BeforeEach
    void setUp() {
        svc = new BiometricAuthService(challengeRepo, registrationRepo, userRepo, jwtService);
    }

    private UserJpaEntity user() {
        UserJpaEntity u = new UserJpaEntity();
        u.setId("u1");
        return u;
    }

    private BiometricRegistrationJpaEntity reg(String secretHash) {
        BiometricRegistrationJpaEntity r = new BiometricRegistrationJpaEntity();
        r.setUserId("u1");
        r.setDeviceId("d1");
        r.setActive(true);
        r.setSecretHash(secretHash);
        return r;
    }

    static String sha256Hex(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Test
    void verify_withoutSecret_isRejected_andMintsNoToken() throws Exception {
        when(userRepo.findById("u1")).thenReturn(Optional.of(user()));
        when(registrationRepo.findByUserIdAndDeviceIdAndActiveTrue("u1", "d1"))
                .thenReturn(Optional.of(reg(sha256Hex("the-real-secret"))));

        // The OLD bypass: (userId, deviceId) with NO secret.
        assertThatThrownBy(() -> svc.verifyBiometric("u1", "d1", null))
                .isInstanceOf(BusinessException.class);
        verify(jwtService, never()).generateToken(anyString());
    }

    @Test
    void verify_withWrongSecret_isRejected_andMintsNoToken() throws Exception {
        when(userRepo.findById("u1")).thenReturn(Optional.of(user()));
        when(registrationRepo.findByUserIdAndDeviceIdAndActiveTrue("u1", "d1"))
                .thenReturn(Optional.of(reg(sha256Hex("the-real-secret"))));

        assertThatThrownBy(() -> svc.verifyBiometric("u1", "d1", "guessed-wrong"))
                .isInstanceOf(BusinessException.class);
        verify(jwtService, never()).generateToken(anyString());
    }

    @Test
    void verify_legacyRowWithNoSecret_isRejected_failClosed() {
        when(userRepo.findById("u1")).thenReturn(Optional.of(user()));
        when(registrationRepo.findByUserIdAndDeviceIdAndActiveTrue("u1", "d1"))
                .thenReturn(Optional.of(reg(null))); // pre-migration registration

        assertThatThrownBy(() -> svc.verifyBiometric("u1", "d1", "anything"))
                .isInstanceOf(BusinessException.class);
        verify(jwtService, never()).generateToken(anyString());
    }

    @Test
    void verify_withCorrectSecret_mintsToken() throws Exception {
        when(userRepo.findById("u1")).thenReturn(Optional.of(user()));
        when(registrationRepo.findByUserIdAndDeviceIdAndActiveTrue("u1", "d1"))
                .thenReturn(Optional.of(reg(sha256Hex("the-real-secret"))));
        when(jwtService.generateToken(eq("u1"), anyString(), anyInt())).thenReturn("TOKEN");

        var result = svc.verifyBiometric("u1", "d1", "the-real-secret");

        assertThat(result).containsEntry("token", "TOKEN");
    }

    @Test
    void register_issuesASecretAndStoresOnlyItsHash() {
        when(userRepo.findById("u1")).thenReturn(Optional.of(user()));
        when(registrationRepo.findByUserIdAndDeviceId("u1", "d1")).thenReturn(Optional.empty());

        String secret = svc.registerDevice("u1", "d1", "iPhone");

        assertThat(secret).isNotBlank();
        var captor = org.mockito.ArgumentCaptor.forClass(BiometricRegistrationJpaEntity.class);
        verify(registrationRepo).save(captor.capture());
        // Stored value is the HASH, never the raw secret.
        assertThat(captor.getValue().getSecretHash()).isNotBlank().isNotEqualTo(secret);
    }
}
