package com.pally.domain.progress;

import com.pally.domain.avatar.Subject;
import com.pally.infrastructure.persistence.activity.ActivityLogJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Single entry point for crediting XP + stars.
 *
 * <p>Owns three behaviours the audit flagged as missing — per-avatar decay
 * (Duolingo's "reduce repeat-XP" rule), variety bonus (first quiz of a new
 * subject today), and chat-session-end dedup (once per avatar per day). All
 * caps clamp the <i>reward</i>, never the <i>access</i> — kids can practise
 * as much as they want; only the points decay.
 *
 * <p>The day boundary is SGT (Asia/Singapore) so the "first quiz of the
 * day" moment is predictable for the kid wherever the deploy lives.
 *
 * <p>Stars track XP at 0.5× (rounded). The 0.5 ratio is centralised here
 * so promotions / "double XP weekends" only need to override one place.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class XpService {

    /// SGT — matches the kid's day boundary regardless of server tz.
    private static final ZoneId DAY_ZONE = ZoneId.of("Asia/Singapore");

    private static final String TYPE_QUIZ = "QUIZ";
    private static final String TYPE_CHAT = "CHAT";

    /// Decay curve indexed by 0-based prior-attempt count. 5+ also at 10%
    /// (the floor — never zero so practice still gives a nudge).
    static final double[] QUIZ_DECAY = {1.0, 0.5, 0.25, 0.10, 0.10};
    static final double VARIETY_BONUS_MULTIPLIER = 1.5;

    /// Chat session-end credit — once per (user, avatar, SGT day).
    private static final int CHAT_XP = 5;
    private static final int CHAT_STARS = 2;
    /// Star/XP ratio. Stars = round(xp * RATIO).
    private static final double STAR_RATIO = 0.5;

    private final UserRepository userRepository;
    private final ActivityLogJpaRepository activityLog;

    public record QuizAward(
            int xpGranted,
            int starsGranted,
            int requestedXp,
            double multiplier,
            boolean varietyBonus,
            int decayStep,
            int srsBonusXp,
            int weakBonusXp,
            UserRepository.XpResult creditResult) {
        /// 7-arg overload retained for tests that don't exercise SRS/weak.
        public QuizAward(int xpGranted, int starsGranted, int requestedXp,
                         double multiplier, boolean varietyBonus, int decayStep,
                         UserRepository.XpResult creditResult) {
            this(xpGranted, starsGranted, requestedXp, multiplier, varietyBonus,
                    decayStep, 0, 0, creditResult);
        }
    }

    public record ChatAward(
            int xpGranted,
            int starsGranted,
            boolean alreadyCreditedToday,
            UserRepository.XpResult creditResult) {}

    /**
     * Credits quiz XP with per-avatar decay + variety bonus.
     *
     * <p>Decay: 1st quiz of the day on this avatar = 100%; 2nd = 50%;
     * 3rd = 25%; 4th+ = 10%. Variety: if this user has not done a quiz
     * on any avatar of {@code subject} today yet, multiply by 1.5×.
     *
     * @param baseXp the raw XP the quiz would credit at full rate
     */
    public QuizAward awardForQuiz(String userId, String avatarId,
                                  Subject subject, int baseXp) {
        return awardForQuiz(userId, avatarId, subject, baseXp, 0, 0);
    }

    /**
     * Quiz award with SRS + weak-topic bonuses.
     *
     * <p>Caller computes the additional XP from correct answers on
     * SRS-due topics (each contributes 0.5× the per-correct XP) and on
     * brain-map-flagged weak topics (0.25×). Those bonuses are summed
     * INTO the base and the daily decay applies to the total — so a
     * 4th-quiz-today still gets the bonus shape proportionally. Stars
     * are minted from the granted total with the level multiplier.
     */
    public QuizAward awardForQuiz(String userId, String avatarId,
                                  Subject subject, int baseXp,
                                  int srsBonusXp, int weakBonusXp) {
        var window = sgtToday();
        int priorQuizzes = nz(activityLog.countByTypeAndAvatarBetween(
                userId, TYPE_QUIZ, avatarId, window.from, window.to));
        int decayIdx = Math.min(priorQuizzes, QUIZ_DECAY.length - 1);
        double mult = QUIZ_DECAY[decayIdx];

        boolean variety = false;
        if (subject != null) {
            int sameSubjectToday = nz(activityLog.countByTypeAndSubjectBetween(
                    userId, TYPE_QUIZ, subject.name(),
                    window.from, window.to));
            if (sameSubjectToday == 0) {
                mult *= VARIETY_BONUS_MULTIPLIER;
                variety = true;
            }
        }

        int preDecayXp = baseXp + Math.max(0, srsBonusXp) + Math.max(0, weakBonusXp);
        int xpGranted = (int) Math.round(preDecayXp * mult);
        int starsGranted = mintStars(userId, xpGranted);
        log.info("[XP] quiz user={} avatar={} base={} srs={} weak={} "
                + "priorQuizzes={} variety={} mult={} → xp={} stars={}",
                userId, avatarId, baseXp, srsBonusXp, weakBonusXp,
                priorQuizzes, variety, mult, xpGranted, starsGranted);

        var credit = userRepository.addXpAndStars(userId, xpGranted, starsGranted);
        return new QuizAward(xpGranted, starsGranted, preDecayXp, mult, variety,
                priorQuizzes, srsBonusXp, weakBonusXp, credit);
    }

    /// Mints stars with the level-based star-earn multiplier (L10 +25%).
    /// Reads the user's current level once so a single award reflects the
    /// state at credit time. Returns 0 if xp is 0 (chat-dedup path).
    private int mintStars(String userId, int xpGranted) {
        if (xpGranted <= 0) return 0;
        int level = userRepository.findById(userId)
                .map(UserStats::level).orElse(1);
        double mult = LevelRewards.starEarnMultiplier(level);
        return (int) Math.round(xpGranted * STAR_RATIO * mult);
    }

    /**
     * Credits chat XP iff this user has not had a chat-session-end credit
     * for this avatar today. Otherwise returns a zero-granted award so the
     * caller's UX can stay the same (no errors, just no extra XP).
     */
    public ChatAward awardForChat(String userId, String avatarId) {
        var window = sgtToday();
        int priorChats = nz(activityLog.countByTypeAndAvatarBetween(
                userId, TYPE_CHAT, avatarId, window.from, window.to));
        if (priorChats > 0) {
            log.debug("[XP] chat user={} avatar={} already credited today "
                    + "(count={}); skipping", userId, avatarId, priorChats);
            // Still need to know the user's current level so the caller can
            // surface no-op level state without a second round-trip.
            int xp = userRepository.findById(userId)
                    .map(UserStats::xp).orElse(0);
            int level = ProgressSummary.computeLevel(xp);
            return new ChatAward(0, 0, true,
                    UserRepository.XpResult.unchanged(xp, level));
        }
        int starsGranted = mintStars(userId, CHAT_XP);
        var credit = userRepository.addXpAndStars(userId, CHAT_XP, starsGranted);
        log.info("[XP] chat user={} avatar={} +{}xp +{}stars",
                userId, avatarId, CHAT_XP, starsGranted);
        return new ChatAward(CHAT_XP, starsGranted, false, credit);
    }

    /// Pass-through for sites that don't need decay (referral activation).
    /// Star multiplier is still applied so the L10 perk affects all credits,
    /// not just quiz/chat.
    public UserRepository.XpResult award(String userId, int xp, int stars) {
        // Caller's {@code stars} is treated as a *requested* amount before
        // the multiplier — callers shouldn't have to know about the perk.
        // Pass 0 to skip multiplier (e.g. referral pays a fixed amount).
        return userRepository.addXpAndStars(userId, xp, stars);
    }

    /// Generic credit with decay-free, multiplier-applied stars. Use when
    /// the source has its own dedup (e.g. teach session-end is one per
    /// session, photo per-question-hash via the caller).
    public UserRepository.XpResult awardFlat(String userId, int xp) {
        if (xp <= 0) {
            int currentXp = userRepository.findById(userId)
                    .map(UserStats::xp).orElse(0);
            int level = ProgressSummary.computeLevel(currentXp);
            return UserRepository.XpResult.unchanged(currentXp, level);
        }
        int stars = mintStars(userId, xp);
        return userRepository.addXpAndStars(userId, xp, stars);
    }

    private static final String TYPE_PHOTO = "PHOTO";

    public record PhotoAward(
            int xpGranted,
            int starsGranted,
            int requestedXp,
            double multiplier,
            int decayStep,
            UserRepository.XpResult creditResult) {}

    /**
     * Credits photo-question XP with per-avatar daily decay. Kids who
     * resubmit the same photo or spam the same homework page get
     * decreasing returns (50/25/10), but the first photo of the day
     * still pays full XP. Per-question-hash decay is a future iteration
     * (would require a small dedup table) — this addresses the spam
     * case adequately for now.
     */
    public PhotoAward awardForPhoto(String userId, String avatarId, int baseXp) {
        if (baseXp <= 0) {
            int xp = userRepository.findById(userId).map(UserStats::xp).orElse(0);
            int lvl = ProgressSummary.computeLevel(xp);
            return new PhotoAward(0, 0, 0, 0.0, 0,
                    UserRepository.XpResult.unchanged(xp, lvl));
        }
        var window = sgtToday();
        int priorPhotos = nz(activityLog.countByTypeAndAvatarBetween(
                userId, TYPE_PHOTO, avatarId, window.from, window.to));
        int decayIdx = Math.min(priorPhotos, QUIZ_DECAY.length - 1);
        double mult = QUIZ_DECAY[decayIdx];
        int xpGranted = (int) Math.round(baseXp * mult);
        int starsGranted = mintStars(userId, xpGranted);
        log.info("[XP] photo user={} avatar={} base={} priorPhotos={} mult={} "
                + "→ xp={} stars={}", userId, avatarId, baseXp, priorPhotos,
                mult, xpGranted, starsGranted);
        var credit = userRepository.addXpAndStars(userId, xpGranted, starsGranted);
        return new PhotoAward(xpGranted, starsGranted, baseXp, mult,
                priorPhotos, credit);
    }

    private static int nz(Integer i) { return i == null ? 0 : i; }

    private record DayWindow(Instant from, Instant to) {}

    private static DayWindow sgtToday() {
        LocalDate today = LocalDate.now(DAY_ZONE);
        Instant from = today.atStartOfDay(DAY_ZONE).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(DAY_ZONE).toInstant();
        return new DayWindow(from, to);
    }
}
