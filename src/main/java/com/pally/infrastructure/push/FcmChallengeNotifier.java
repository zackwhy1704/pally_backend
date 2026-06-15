package com.pally.infrastructure.push;

import com.pally.domain.challenge.ChallengeNotifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Adapter wiring the challenge {@link ChallengeNotifier} port to FCM push. */
@Component
@RequiredArgsConstructor
public class FcmChallengeNotifier implements ChallengeNotifier {

    private final FcmService fcmService;

    @Override
    public void sendToUser(String userId, String title, String body) {
        fcmService.sendToUser(userId, title, body);
    }
}
