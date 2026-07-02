package com.pally.domain.weakness;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarKind;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.port.WikiCompilerPort;
import com.pally.domain.knowledge.usecase.WikiPagePersistenceService;
import com.pally.domain.weakness.WeaknessStateStore.WeaknessState;
import com.pally.infrastructure.config.AiTaskExecutorConfig;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * The WEAKNESS_PROFILE head, end to end — compiles a student's performance
 * signals into a private per-(userId, subject) weakness brain through the SAME
 * wiki harness the notes/marking heads use. Dormant behind {@code
 * weakness.profile.enabled} until validated: every entry point returns early
 * when the flag is off, so live behaviour is unchanged.
 *
 * <p>Scope is derived from the avatar itself (owner = student userId, plus the
 * subject + WEAKNESS_PROFILE kind), so no mapping table is needed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WeaknessProfileService {

    private final AvatarRepository avatarRepository;
    private final WeaknessSignalRepository signalRepository;
    private final WeaknessSignalService signalService;
    private final WikiCompilerPort wikiCompiler;
    private final WikiPagePersistenceService persistenceService;
    private final WikiRepository wikiRepository;
    private final WeaknessStateStore stateStore;

    /** How many recovered topics to remember for the "you improved" celebration. */
    private static final int MAX_WINS = 5;

    @Value("${weakness.profile.enabled:false}")
    private boolean enabled;

    /** Exposed so callers skip enqueuing an async trigger when the pilot is off. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Debounced trigger — call after a mastery update (PROVE→mastery). Runs async
     * so it never adds latency to module completion, and only (re)compiles when
     * the student's weak-set MATERIALLY changed since last time (no
     * compile-per-session storm). Records recovered topics as "wins". No-op when
     * the flag is off. Never throws into the caller.
     */
    @Async(AiTaskExecutorConfig.AI_TASK_EXECUTOR)
    public void onMasteryUpdated(String userId, String sourceAvatarId) {
        if (!enabled) return;
        try {
            Avatar source = avatarRepository.findById(sourceAvatarId).orElse(null);
            if (source == null || source.getSubject() == null) return;
            Subject subject = source.getSubject();

            List<String> currentWeak =
                    signalService.weakSlugs(signalRepository.findTopicMastery(userId, sourceAvatarId));
            String currentSig = String.join(",", currentWeak);

            var prev = stateStore.find(userId, subject);
            String prevSig = prev.map(WeaknessState::weakSlugs).orElse("");
            if (currentSig.equals(prevSig)) {
                log.debug("[Weakness] debounce skip user={} subject={} weak-set unchanged",
                        hash(userId), subject);
                return;
            }

            // Wins = topics that were weak before and no longer are.
            List<String> prevWeak = prevSig.isBlank()
                    ? List.of() : Arrays.asList(prevSig.split(","));
            List<String> recovered = prevWeak.stream()
                    .filter(s -> !currentWeak.contains(s)).toList();
            String recentWins = recovered.isEmpty()
                    ? prev.map(WeaknessState::recentWins).orElse("")
                    : String.join(",", recovered.stream().limit(MAX_WINS).toList());

            stateStore.upsert(userId, subject, currentSig, recentWins);
            log.info("[Weakness] recompile user={} subject={} weak={} recovered={}",
                    hash(userId), subject, currentWeak.size(), recovered.size());
            rebuildFor(source);
        } catch (Exception e) {
            log.warn("[Weakness] onMasteryUpdated failed (non-fatal): {}", e.getMessage());
        }
    }

    /** Topics the student recently recovered — for the "you improved" celebration. */
    public List<String> recentWins(String userId, Subject subject) {
        if (!enabled) return List.of();
        return stateStore.find(userId, subject)
                .map(WeaknessState::recentWins)
                .filter(s -> s != null && !s.isBlank())
                .map(s -> Arrays.asList(s.split(",")))
                .orElseGet(List::of);
    }

    /** Privacy: never log a raw userId. */
    private static String hash(String userId) {
        return userId == null ? "?" : Integer.toHexString(userId.hashCode());
    }

    /**
     * Rebuilds the student's weakness brain for the subject of [sourceAvatar]
     * from their quiz history under that avatar. No-op when the flag is off, or
     * when there isn't enough signal to be worth compiling.
     */
    public void rebuildFor(Avatar sourceAvatar) {
        if (!enabled) return;
        if (sourceAvatar == null || sourceAvatar.getSubject() == null) return;

        String userId = sourceAvatar.getUserId();
        Subject subject = sourceAvatar.getSubject();

        var mastery = signalRepository.findTopicMastery(userId, sourceAvatar.getId());
        String report = signalService.renderReport(subject, mastery);
        if (report == null) {
            log.debug("[Weakness] no usable signal for user={} subject={}", userId, subject);
            return;
        }

        Avatar weaknessAvatar = resolveOrCreate(userId, subject);

        // The compiler input is a file list; feed the rendered report as a single
        // in-memory TEXT source (never persisted, so no provenance rows needed).
        KnowledgeFile signalFile = KnowledgeFile.reconstitute(
                IdGenerator.newId(), weaknessAvatar.getId(), userId,
                "performance-signals", null, 1,
                KnowledgeFile.UploadType.TEXT, KnowledgeFile.Status.READY,
                Instant.now(), report);

        List<WikiPage> existing = wikiRepository.findByAvatarId(weaknessAvatar.getId());
        var output = wikiCompiler.compileWithTier(
                weaknessAvatar, List.of(signalFile), existing);
        persistenceService.persistDrafts(weaknessAvatar, output.drafts(), List.of());
        log.info("[Weakness] rebuilt profile user={} subject={} pages={}",
                userId, subject, output.drafts().size());
    }

    /** The student's compiled weakness pages for grounding — empty when off/absent. */
    public List<WikiPage> weaknessPagesFor(String userId, Subject subject) {
        if (!enabled) return List.of();
        return findExisting(userId, subject)
                .map(a -> wikiRepository.findActiveByAvatarId(a.getId()))
                .orElseGet(List::of);
    }

    /**
     * Finds the student's WEAKNESS_PROFILE avatar for the subject, creating it
     * lazily. (A rare concurrent double-create is harmless for this dormant
     * pilot — both would compile to the same pages; add a unique constraint if
     * it's ever promoted to always-on.)
     */
    Avatar resolveOrCreate(String userId, Subject subject) {
        return findExisting(userId, subject).orElseGet(() -> {
            Avatar a = Avatar.create(
                    userId, subject.label() + " Weakness Profile", subject,
                    CharacterType.MOCHI);
            a.markWeaknessProfile();
            return avatarRepository.save(a);
        });
    }

    private java.util.Optional<Avatar> findExisting(String userId, Subject subject) {
        return avatarRepository.findByUserId(userId).stream()
                .filter(a -> a.getKind() == AvatarKind.WEAKNESS_PROFILE)
                .filter(a -> a.getSubject() == subject)
                .findFirst();
    }
}
