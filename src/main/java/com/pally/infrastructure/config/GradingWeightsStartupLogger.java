package com.pally.infrastructure.config;

import com.pally.domain.module.GradingSignal;
import com.pally.domain.module.GradingWeights;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Logs the LIVE, fully-resolved grading trust weights once at startup:
 * <pre>[GradingWeights] selfReport=0.30 deterministic=1.0 ungraded=0.0 quoted=0.30 MATCH</pre>
 *
 * <p><b>Why this exists.</b> {@code GradingWeights} is
 * {@code @ConfigurationProperties("grading")}-driven, so {@code grading.self-report-weight}
 * can be overridden by a {@code GRADING_SELF_REPORT_WEIGHT} environment variable on
 * Railway. {@code GradingWeightsGuardTest} pins the compiled default and scans
 * {@code src/main/resources} for overrides, but a build-time test provably CANNOT
 * observe a runtime env var. This closes that gap: the deployed number becomes
 * readable from a running instance's boot log.
 *
 * <p>Deliberately a log line and NOT an {@code /actuator/info} field:
 * {@code /actuator/info} is {@code permitAll} in {@code SecurityConfig}, so anything
 * added there is publicly readable. Grading weights are internal scoring policy and
 * do not belong on an unauthenticated endpoint.
 *
 * <p>Runs on {@link ApplicationReadyEvent} rather than mirroring
 * {@link BuildInfoLogger}'s {@code ApplicationEnvironmentPreparedEvent}: the whole
 * point is the POST-binding value, and no {@code @ConfigurationProperties} bean
 * exists that early.
 *
 * <p>Read-only. It never mutates the weight — a drift is reported, never
 * "corrected", because silently forcing a value would hide the very
 * misconfiguration this is here to surface.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GradingWeightsStartupLogger {

    /**
     * The SELF_REPORT weight quoted in external material. Kept in sync with
     * {@code GradingWeightsGuardTest}; if the live value diverges from this, one of
     * the two is wrong and the boot log says so loudly.
     */
    public static final double QUOTED_SELF_REPORT_WEIGHT = 0.30;

    private static final double EPSILON = 1e-9;

    private final GradingWeights gradingWeights;

    @EventListener(ApplicationReadyEvent.class)
    public void logResolvedWeights() {
        double live = gradingWeights.getSelfReportWeight();
        double deterministic = gradingWeights.weightFor(GradingSignal.DETERMINISTIC);
        double ungraded = gradingWeights.weightFor(GradingSignal.UNGRADED);
        boolean matchesQuoted = Math.abs(live - QUOTED_SELF_REPORT_WEIGHT) <= EPSILON;

        if (matchesQuoted) {
            log.info("[GradingWeights] selfReport={} deterministic={} ungraded={} quoted={} MATCH",
                    live, deterministic, ungraded, QUOTED_SELF_REPORT_WEIGHT);
        } else {
            // LOUD: mastery is being trust-weighted with a number that differs from
            // what is being claimed externally. Either the env override is wrong or
            // the external claim is stale — do not let this pass unnoticed.
            log.warn("[GradingWeights] selfReport={} DIFFERS from externally-quoted {} "
                            + "(grading.self-report-weight overridden, likely via "
                            + "GRADING_SELF_REPORT_WEIGHT) — mastery is trust-weighted "
                            + "differently than documented; fix the config or the claim",
                    live, QUOTED_SELF_REPORT_WEIGHT);
        }
    }
}
