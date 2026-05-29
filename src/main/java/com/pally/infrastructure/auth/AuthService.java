package com.pally.infrastructure.auth;

import com.pally.api.auth.dto.AuthResponse;
import com.pally.domain.shop.CharacterShopService;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserJpaRepository userRepo;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final CharacterShopService characterShopService;
    private final com.pally.domain.progress.BadgeService badgeService;

    @Transactional
    public AuthResponse register(String email, String password, String displayName) {
        if (userRepo.existsByEmail(email)) {
            throw new BusinessException("Email already registered", 409);
        }

        UserJpaEntity user = new UserJpaEntity();
        user.setId(IdGenerator.newId());
        user.setEmail(email);
        user.setDisplayName(displayName != null ? displayName : "Player");
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setStars(0);
        user.setXp(0);
        user.setLevel(1);
        user.setStreakDays(0);
        user.setCreatedAt(Instant.now());
        user.setSetupComplete(false);
        userRepo.save(user);

        log.info("[Auth] Registered new user id={} email={}", user.getId(), email);
        characterShopService.seedDefaultUnlocks(user.getId());
        String token = jwtService.generateToken(user.getId());
        return new AuthResponse(user.getId(), token, true, false);
    }

    @Transactional
    public AuthResponse login(String email, String password) {
        UserJpaEntity user = userRepo.findByEmail(email)
                .orElseThrow(() -> new BusinessException("Invalid email or password", 401));

        if (user.getPasswordHash() == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException("Invalid email or password", 401);
        }

        // Daily login streak update (idempotent for same-day logins).
        updateLoginStreak(user);

        // Award streak badges if applicable
        try {
            badgeService.checkAndGrantMilestones(user.getId());
        } catch (Exception ignored) {}

        log.info("[Auth] Login success id={} email={} streak={}",
                user.getId(), email, user.getStreakDays());
        String token = jwtService.generateToken(user.getId());
        return new AuthResponse(user.getId(), token, false, user.isSetupComplete());
    }

    /// Increments streakDays if the user logs in on consecutive days; resets
    /// to 1 on a broken streak; no-op on same-day logins. Awards bonus XP for
    /// the first login of a new day (up to 50 XP cap).
    private void updateLoginStreak(UserJpaEntity user) {
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate last = user.getLastActiveDate();
        if (last != null && last.equals(today)) {
            return; // already counted today
        }
        int newStreak;
        if (last != null && last.equals(today.minusDays(1))) {
            newStreak = user.getStreakDays() + 1;
        } else {
            newStreak = 1;
        }
        user.setStreakDays(newStreak);
        user.setLastActiveDate(today);
        // Streak bonus XP — 5 per day, capped at 50
        int bonus = Math.min(newStreak * 5, 50);
        user.setXp(user.getXp() + bonus);
        user.setLevel(com.pally.domain.progress.ProgressSummary.computeLevel(user.getXp()));
        userRepo.save(user);
        log.info("[Auth] Streak day {} → +{} XP", newStreak, bonus);
    }

    @Transactional
    public AuthResponse signInWithSocial(String email, String displayName) {
        UserJpaEntity user = userRepo.findByEmail(email).orElseGet(() -> {
            UserJpaEntity u = new UserJpaEntity();
            u.setId(IdGenerator.newId());
            u.setEmail(email);
            u.setDisplayName(displayName != null ? displayName : "Player");
            u.setStars(0);
            u.setXp(0);
            u.setLevel(1);
            u.setStreakDays(0);
            u.setCreatedAt(Instant.now());
            u.setSetupComplete(false);
            return userRepo.save(u);
        });

        boolean isNew = user.getCreatedAt().isAfter(Instant.now().minusSeconds(5));
        if (isNew) {
            characterShopService.seedDefaultUnlocks(user.getId());
        }

        // Same streak + badge progression as email login. Without this,
        // Google / Apple users never accumulate streak days even with
        // daily activity.
        updateLoginStreak(user);
        try {
            badgeService.checkAndGrantMilestones(user.getId());
        } catch (Exception ignored) {
            // never block sign-in on badge math
        }

        log.info("[Auth] Social sign-in id={} email={} new={} streak={}",
                user.getId(), email, isNew, user.getStreakDays());
        String token = jwtService.generateToken(user.getId());
        return new AuthResponse(user.getId(), token, isNew, user.isSetupComplete());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getUser(String userId) {
        UserJpaEntity user = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        return Map.of(
                "userId", user.getId(),
                "email", user.getEmail() != null ? user.getEmail() : "",
                "displayName", user.getDisplayName() != null ? user.getDisplayName() : "",
                "setupComplete", user.isSetupComplete(),
                "childName", user.getChildName() != null ? user.getChildName() : ""
        );
    }

    @Transactional
    public void deleteAccount(String userId) {
        UserJpaEntity user = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        userRepo.deleteById(userId);
        log.info("[Auth] Deleted account id={} email={}", user.getId(), user.getEmail());
    }

    @Transactional
    public void updateChildName(String userId, String childName) {
        UserJpaEntity user = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        if (childName != null && !childName.isBlank()) {
            user.setChildName(childName);
            userRepo.save(user);
            log.info("[Auth] Updated child name id={} name={}", userId, childName);
        }
    }

    @Transactional
    public AuthResponse completeSetup(String userId, String childName, Integer yearLevel, String curriculum) {
        UserJpaEntity user = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));

        user.setChildName(childName);
        user.setYearLevel(yearLevel);
        user.setCurriculum(curriculum);
        user.setSetupComplete(true);
        userRepo.save(user);

        log.info("[Auth] Setup complete id={} child={}", userId, childName);
        String token = jwtService.generateToken(userId);
        return new AuthResponse(userId, token, false, true);
    }
}
