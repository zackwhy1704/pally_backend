package com.pally.infrastructure.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pally.domain.module.GradingWeights;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the runtime-observability gap-closer: the deployed self-report weight must be
 * readable from a running instance's boot log, and a drift from the externally-quoted
 * value must be WARN, not a quietly-different INFO line.
 *
 * <p>This is the half {@code GradingWeightsGuardTest} structurally cannot cover — that
 * test sees the compiled default and config files; this one covers what an env
 * override actually produces at runtime.
 */
class GradingWeightsStartupLoggerTest {

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void setUp() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = ctx.getLogger(GradingWeightsStartupLogger.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private GradingWeights weightsWith(double selfReport) {
        GradingWeights w = new GradingWeights();
        w.setSelfReportWeight(selfReport);
        return w;
    }

    @Test
    void logsTheLiveResolvedValue_atInfo_whenItMatchesTheQuotedNumber() {
        new GradingWeightsStartupLogger(weightsWith(0.30)).logResolvedWeights();

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage())
                .contains("selfReport=0.3")
                .contains("MATCH");
    }

    @Test
    void warnsLoudly_whenAnEnvOverrideDivergesFromTheQuotedNumber() {
        // Simulates GRADING_SELF_REPORT_WEIGHT=0.5 on Railway — the exact scenario no
        // build-time test can observe. If this ever happens in a real deploy, the
        // externally-quoted 0.30 is wrong and the boot log must say so.
        new GradingWeightsStartupLogger(weightsWith(0.5)).logResolvedWeights();

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel())
                .as("a silent INFO would let a mis-set weight pass unnoticed")
                .isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .contains("DIFFERS")
                .contains("0.5")
                .contains("0.3");
    }

    @Test
    void neverMutatesTheWeight_aDriftIsReportedNotCorrected() {
        // Silently forcing the value back to 0.30 would hide the misconfiguration
        // this logger exists to surface.
        GradingWeights drifted = weightsWith(0.5);

        new GradingWeightsStartupLogger(drifted).logResolvedWeights();

        assertThat(drifted.getSelfReportWeight()).isEqualTo(0.5);
    }

    @Test
    void quotedConstantMatchesTheCompiledDefault() {
        // Guards the two-places-one-number problem: if someone changes the
        // GradingWeights default without updating the quoted constant, every deploy
        // would WARN spuriously and the warning would start being ignored.
        assertThat(GradingWeightsStartupLogger.QUOTED_SELF_REPORT_WEIGHT)
                .isEqualTo(new GradingWeights().getSelfReportWeight());
    }
}
