package com.pally.infrastructure.push;

import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Optional;

/**
 * Firebase Cloud Messaging push notification service. If
 * FIREBASE_SERVICE_ACCOUNT_JSON is not set, all sends are no-ops with a
 * warning log — safe for dev and test environments.
 */
@Service
@Slf4j
public class FcmService {

    private final UserJpaRepository userRepo;
    private final String serviceAccountJson;
    private boolean initialized = false;

    public FcmService(UserJpaRepository userRepo,
                      @Value("${FIREBASE_SERVICE_ACCOUNT_JSON:}") String serviceAccountJson) {
        this.userRepo = userRepo;
        this.serviceAccountJson = serviceAccountJson;
    }

    @PostConstruct
    void init() {
        if (serviceAccountJson == null || serviceAccountJson.isBlank()) {
            log.warn("[FCM] FIREBASE_SERVICE_ACCOUNT_JSON not set — push notifications disabled");
            return;
        }
        try {
            // Decode base64 and initialize Firebase Admin SDK
            byte[] decoded = java.util.Base64.getDecoder().decode(serviceAccountJson);
            var credentials = com.google.auth.oauth2.GoogleCredentials
                    .fromStream(new java.io.ByteArrayInputStream(decoded));
            var options = com.google.firebase.FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .build();
            if (com.google.firebase.FirebaseApp.getApps().isEmpty()) {
                com.google.firebase.FirebaseApp.initializeApp(options);
            }
            initialized = true;
            log.info("[FCM] Firebase initialized successfully");
        } catch (Exception e) {
            log.error("[FCM] Firebase initialization failed: {}", e.getMessage());
        }
    }

    /** Whether Firebase is initialized and push sending is active. */
    public boolean isConfigured() {
        return initialized;
    }

    /**
     * Send a push notification to a specific user by looking up their FCM token.
     * No-op if the user has no token or Firebase is not configured.
     */
    public void sendToUser(String userId, String title, String body) {
        if (!initialized) {
            log.debug("[FCM] Not initialized — skipping push to user={}", userId);
            return;
        }
        Optional<UserJpaEntity> userOpt = userRepo.findById(userId);
        if (userOpt.isEmpty()) {
            log.warn("[FCM] User not found: {}", userId);
            return;
        }
        String token = userOpt.get().getFcmToken();
        if (token == null || token.isBlank()) {
            log.debug("[FCM] No FCM token for user={}", userId);
            return;
        }
        try {
            var message = com.google.firebase.messaging.Message.builder()
                    .setToken(token)
                    .setNotification(com.google.firebase.messaging.Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build())
                    .build();
            String messageId = com.google.firebase.messaging.FirebaseMessaging
                    .getInstance().send(message);
            log.info("[FCM] Push sent user={} messageId={}", userId, messageId);
        } catch (Exception e) {
            log.error("[FCM] Push failed user={}: {}", userId, e.getMessage());
        }
    }
}
