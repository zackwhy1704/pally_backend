package com.pally.infrastructure.auth;

import com.pally.infrastructure.persistence.auth.BiometricChallengeJpaEntity;
import com.pally.infrastructure.persistence.auth.BiometricChallengeJpaRepository;
import com.pally.infrastructure.persistence.auth.BiometricRegistrationJpaEntity;
import com.pally.infrastructure.persistence.auth.BiometricRegistrationJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class BiometricAuthService {

    private static final int CHALLENGE_BYTES = 32;
    private static final long CHALLENGE_TTL_SECONDS = 300; // 5 minutes
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCKOUT_MINUTES = 15;

    private final BiometricChallengeJpaRepository challengeRepo;
    private final BiometricRegistrationJpaRepository registrationRepo;
    private final UserJpaRepository userRepo;
    private final JwtService jwtService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public Map<String, Object> createChallenge(String userId) {
        UserJpaEntity user = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));

        byte[] randomBytes = new byte[CHALLENGE_BYTES];
        secureRandom.nextBytes(randomBytes);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        String hash = sha256Hex(challenge);

        Instant expiresAt = Instant.now().plus(CHALLENGE_TTL_SECONDS, ChronoUnit.SECONDS);

        BiometricChallengeJpaEntity entity = new BiometricChallengeJpaEntity();
        entity.setId(IdGenerator.newId());
        entity.setUserId(userId);
        entity.setChallengeHash(hash);
        entity.setExpiresAt(expiresAt);
        entity.setUsed(false);
        entity.setCreatedAt(Instant.now());
        challengeRepo.save(entity);

        log.info("[Biometric] Challenge created for user={}", userId);
        return Map.of(
                "challenge", challenge,
                "expiresAt", expiresAt.toString()
        );
    }

    @Transactional
    public Map<String, Object> verifyBiometric(String userId, String deviceId, String deviceSecret) {
        if (userId == null || deviceId == null) {
            throw new BusinessException("userId and deviceId are required", 400);
        }

        UserJpaEntity user = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));

        if (user.getBiometricLockedUntil() != null && Instant.now().isBefore(user.getBiometricLockedUntil())) {
            throw new BusinessException("Account temporarily locked due to too many failed attempts", 423);
        }

        BiometricRegistrationJpaEntity reg = registrationRepo
                .findByUserIdAndDeviceIdAndActiveTrue(userId, deviceId)
                .orElseThrow(() -> {
                    incrementFailedAttempts(user);
                    return new BusinessException("Device not registered for biometric auth", 401);
                });

        // PROOF-OF-POSSESSION: (userId, deviceId) are non-secret, so they alone must
        // NOT mint a token. Require the device secret issued at register time (stored
        // only in the device's secure storage) and constant-time compare its hash.
        // A legacy row with no secret fails closed → the device must re-register.
        if (reg.getSecretHash() == null
                || deviceSecret == null
                || !MessageDigest.isEqual(
                        sha256Hex(deviceSecret).getBytes(StandardCharsets.UTF_8),
                        reg.getSecretHash().getBytes(StandardCharsets.UTF_8))) {
            incrementFailedAttempts(user);
            throw new BusinessException("Biometric verification failed", 401);
        }

        user.setBiometricFailedAttempts(0);
        user.setBiometricLockedUntil(null);
        userRepo.save(user);

        String token = jwtService.generateToken(userId, JwtService.DEFAULT_ROLE, user.getSessionEpoch());
        log.info("[Biometric] Verification success for user={} device={}", userId, deviceId);
        return Map.of(
                "token", token,
                "userId", userId,
                "setupComplete", user.isSetupComplete()
        );
    }

    /// Registers (or re-registers) a device and returns a fresh, high-entropy
    /// device SECRET — returned ONCE, over the authenticated register call. The
    /// client stores it in the device's secure storage and presents it at verify;
    /// only its SHA-256 hash is kept here. Re-register rotates the secret.
    @Transactional
    public String registerDevice(String userId, String deviceId, String deviceName) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new BusinessException("deviceId is required", 400);
        }

        userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));

        byte[] secretBytes = new byte[CHALLENGE_BYTES];
        secureRandom.nextBytes(secretBytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        String secretHash = sha256Hex(secret);

        BiometricRegistrationJpaEntity existing = registrationRepo
                .findByUserIdAndDeviceId(userId, deviceId)
                .orElse(null);

        if (existing != null) {
            existing.setDeviceName(deviceName);
            existing.setActive(true);
            existing.setRegisteredAt(Instant.now());
            existing.setSecretHash(secretHash);
            registrationRepo.save(existing);
            log.info("[Biometric] Re-registered device={} for user={}", deviceId, userId);
        } else {
            BiometricRegistrationJpaEntity entity = new BiometricRegistrationJpaEntity();
            entity.setId(IdGenerator.newId());
            entity.setUserId(userId);
            entity.setDeviceId(deviceId);
            entity.setDeviceName(deviceName);
            entity.setRegisteredAt(Instant.now());
            entity.setActive(true);
            entity.setSecretHash(secretHash);
            registrationRepo.save(entity);
            log.info("[Biometric] Registered new device={} for user={}", deviceId, userId);
        }
        return secret;
    }

    private void incrementFailedAttempts(UserJpaEntity user) {
        int attempts = user.getBiometricFailedAttempts() + 1;
        user.setBiometricFailedAttempts(attempts);
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            user.setBiometricLockedUntil(Instant.now().plus(LOCKOUT_MINUTES, ChronoUnit.MINUTES));
            log.warn("[Biometric] Locking user={} for {} minutes after {} failed attempts",
                    user.getId(), LOCKOUT_MINUTES, attempts);
        }
        userRepo.save(user);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
