package com.pally.api.classroom;

import com.pally.domain.classroom.ClassroomSessionService;
import com.pally.domain.classroom.dto.ClassroomAttackRequest;
import com.pally.domain.classroom.dto.ClassroomAttackResponse;
import com.pally.domain.classroom.dto.ClassroomJoinRequest;
import com.pally.domain.classroom.dto.ClassroomJoinResponse;
import com.pally.shared.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Student-facing classroom session endpoints, scoped under
 * {@code /api/v1/avatars/{avatarId}}. Thin delegator to
 * {@link ClassroomSessionService} for join/attack; {@link #stream} is the one
 * exception (same shape as {@code ChatController.chat} — SSE needs the raw
 * response to control the content-type/flush timing Spring's normal JSON
 * return path can't).
 */
@RestController
@RequestMapping("/api/v1/avatars/{avatarId}/classroom-sessions")
@RequiredArgsConstructor
@Slf4j
public class ClassroomSessionController {

    private final ClassroomSessionService classroomSessionService;

    @PostMapping("/join")
    public ResponseEntity<ApiResponse<ClassroomJoinResponse>> join(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @RequestBody ClassroomJoinRequest request) {
        return ResponseEntity.ok(ApiResponse.success(classroomSessionService.join(
                userId, avatarId, request.joinCode(), request.nickname())));
    }

    @PostMapping("/{sessionId}/attack")
    public ResponseEntity<ApiResponse<ClassroomAttackResponse>> attack(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @PathVariable String sessionId,
            @RequestBody ClassroomAttackRequest request) {
        return ResponseEntity.ok(ApiResponse.success(classroomSessionService.attack(
                sessionId, userId, avatarId, request.participantToken(),
                request.questionId(), request.selectedIndex())));
    }

    /**
     * Live question/HP/hit broadcast for one joined participant. Reuses
     * chat's exact SSE wire mechanics: raw {@link HttpServletResponse},
     * {@code text/event-stream}, an immediate {@code : connected} flush so
     * the client knows it's connected before anything else happens, then
     * {@code event:}/{@code data:} framing per emitted event. Blocks this
     * request's thread for the session's duration — the same tradeoff chat
     * already accepts for one AI turn, just longer; ends when the teacher
     * ends the session (the sink completes) or the connection breaks
     * (write throws, caught below).
     */
    @GetMapping("/{sessionId}/stream")
    public void stream(
            @PathVariable String avatarId,
            @PathVariable String sessionId,
            @RequestParam String participantToken,
            HttpServletResponse response) throws java.io.IOException {
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        java.io.PrintWriter writer = response.getWriter();
        writer.write(": connected\n\n");
        writer.flush();

        try {
            classroomSessionService.stream(sessionId, participantToken)
                    .toIterable()
                    .forEach(event -> {
                        writer.write("event: " + event.type() + "\n");
                        String payload = event.payload();
                        if (payload.contains("\n")) {
                            for (String line : payload.split("\n", -1)) {
                                writer.write("data: " + line + "\n");
                            }
                        } else {
                            writer.write("data: " + payload + "\n");
                        }
                        writer.write("\n");
                        writer.flush();
                    });
        } catch (Exception e) {
            log.warn("[Classroom] SSE stream ended session={}: {}", sessionId, e.getMessage());
        }
    }
}
