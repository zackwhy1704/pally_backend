package com.pally.domain.classroom.dto;

/**
 * @param participantToken opaque, session-scoped only — the client uses it to
 *                          authenticate subsequent attack/stream calls for
 *                          this session. Never a persistent identity.
 */
public record ClassroomJoinResponse(
        String participantToken,
        String nickname,
        ClassroomStateResponse state) {
}
