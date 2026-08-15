package com.pally.domain.classroom;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory, per-instance broadcaster for live classroom sessions — the SAME
 * "single Railway instance, no distributed state" assumption this codebase
 * already documents for the daily-quiz cache/single-flight
 * (GetDailyQuizUseCase). Two responsibilities, both intentionally ephemeral:
 *
 * <ol>
 *   <li>Participant nicknames: {@code sessionId -> (participantToken ->
 *       nickname)}. NEVER written to the database — this map IS the only
 *       place a nickname exists, and {@link #forgetSession} drops it
 *       irrecoverably when the session ends.</li>
 *   <li>A multicast {@link Sinks.Many} per session so every joined student's
 *       SSE connection receives the SAME question/HP/hit events — chat's
 *       existing SSE transport (raw {@code HttpServletResponse} writer,
 *       {@code text/event-stream}, {@code event:}/{@code data:} framing) is
 *       reused verbatim for the wire format; that transport was built for one
 *       request driving one AI-response Flux, which is inherently
 *       single-consumer, so the fan-out itself (many subscribers to one
 *       session's stream) is new plumbing — there is no existing broadcast
 *       primitive to reuse for that part.</li>
 * </ol>
 *
 * <p>A per-session lock serializes attack processing so two students
 * answering the same live question in the same instant can't both land a hit
 * on it — same single-instance assumption as the map above; the SQL-level
 * atomicity a distributed deployment would need is explicitly out of v1 scope,
 * matching the "no new WebSocket layer, no distributed state" instruction.
 */
@Component
@Slf4j
public class ClassroomEventBus {

    public record ClassroomStreamEvent(String type, String payload) {}

    private final Map<String, Map<String, String>> nicknamesBySession = new ConcurrentHashMap<>();
    private final Map<String, Sinks.Many<ClassroomStreamEvent>> sinksBySession = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locksBySession = new ConcurrentHashMap<>();

    public void registerParticipant(String sessionId, String participantToken, String nickname) {
        nicknamesBySession.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .put(participantToken, nickname);
    }

    public String nicknameFor(String sessionId, String participantToken) {
        var map = nicknamesBySession.get(sessionId);
        return map == null ? null : map.get(participantToken);
    }

    public boolean hasParticipant(String sessionId, String participantToken) {
        var map = nicknamesBySession.get(sessionId);
        return map != null && map.containsKey(participantToken);
    }

    public int participantCount(String sessionId) {
        var map = nicknamesBySession.get(sessionId);
        return map == null ? 0 : map.size();
    }

    public ReentrantLock lockFor(String sessionId) {
        return locksBySession.computeIfAbsent(sessionId, k -> new ReentrantLock());
    }

    public Flux<ClassroomStreamEvent> streamFor(String sessionId) {
        return sinkFor(sessionId).asFlux();
    }

    public void publish(String sessionId, String type, String payload) {
        var result = sinkFor(sessionId).tryEmitNext(new ClassroomStreamEvent(type, payload));
        if (result.isFailure()) {
            log.warn("[Classroom] broadcast failed session={} type={} result={}",
                    sessionId, type, result);
        }
    }

    /** Ends the stream for every connected participant and irrecoverably
     *  drops the session's nicknames and lock. */
    public void forgetSession(String sessionId) {
        var sink = sinksBySession.remove(sessionId);
        if (sink != null) {
            sink.tryEmitComplete();
        }
        nicknamesBySession.remove(sessionId);
        locksBySession.remove(sessionId);
    }

    private Sinks.Many<ClassroomStreamEvent> sinkFor(String sessionId) {
        // autoCancel=false: the no-arg overload defaults to true, which
        // completes the sink the moment its subscriber count hits zero — a
        // real bug here, not just a test artifact. A participant's connection
        // blipping to zero momentarily (reconnect, backgrounded app) must
        // never silently end the broadcast for everyone else still connected.
        return sinksBySession.computeIfAbsent(sessionId,
                k -> Sinks.many().multicast().onBackpressureBuffer(256, false));
    }
}
