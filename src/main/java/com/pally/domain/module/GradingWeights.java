package com.pally.domain.module;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Config-driven trust weights for mastery. DETERMINISTIC is full weight (1.0),
 * UNGRADED contributes nothing (0.0); only SELF_REPORT is tunable
 * ({@code grading.self-report-weight}, default 0.25) so a self-reported answer
 * moves mastery a quarter as much as a server-verified one.
 */
@Component
@ConfigurationProperties(prefix = "grading")
public class GradingWeights {

    /** Weight of a SELF_REPORT signal relative to DETERMINISTIC (1.0). */
    private double selfReportWeight = 0.30;

    public double getSelfReportWeight() { return selfReportWeight; }
    public void setSelfReportWeight(double w) { this.selfReportWeight = w; }

    /** Trust weight for a signal; UNGRADED is always 0 (contributes nothing). */
    public double weightFor(GradingSignal signal) {
        if (signal == null) return 1.0; // legacy rows (pre-signal-typing) keep old full weight
        return switch (signal) {
            case DETERMINISTIC -> 1.0;
            case SELF_REPORT   -> selfReportWeight;
            case UNGRADED      -> 0.0;
        };
    }
}
