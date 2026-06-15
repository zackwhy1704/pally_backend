package com.pally.domain.challenge;

/**
 * Domain port for notifying a user about a challenge event. The push adapter
 * (FCM) lives in {@code infrastructure/push} so the domain never imports it.
 */
public interface ChallengeNotifier {
    void sendToUser(String userId, String title, String body);
}
