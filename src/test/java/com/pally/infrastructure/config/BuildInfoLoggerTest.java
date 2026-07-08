package com.pally.infrastructure.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The build line must announce the commit+profile and go LOUD (WARN, "DEFAULT!") when
 * no profile is set — the exact condition that silently ran prod with dev security.
 */
class BuildInfoLoggerTest {

    private final ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(BuildInfoLogger.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private void attach() {
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private void fire(MockEnvironment env) {
        attach();
        new BuildInfoLogger().onApplicationEvent(new ApplicationEnvironmentPreparedEvent(
                new DefaultBootstrapContext(), new SpringApplication(), new String[]{}, env));
    }

    @Test
    void noProfile_logsWarnWithDefaultMarker() {
        var env = new MockEnvironment();
        env.setProperty("RAILWAY_GIT_COMMIT_SHA", "deadbeef");
        fire(env);

        var evt = appender.list.stream().filter(e -> e.getFormattedMessage().contains("[Build]")).findFirst();
        assertThat(evt).isPresent();
        assertThat(evt.get().getLevel()).isEqualTo(Level.WARN);              // LOUD
        assertThat(evt.get().getFormattedMessage()).contains("profile=DEFAULT!");
        assertThat(evt.get().getFormattedMessage()).contains("deadbeef");    // the SHA
    }

    @Test
    void withProfile_logsInfoWithProfileName() {
        var env = new MockEnvironment();
        env.setActiveProfiles("prod");
        fire(env);

        var evt = appender.list.stream().filter(e -> e.getFormattedMessage().contains("[Build]")).findFirst();
        assertThat(evt).isPresent();
        assertThat(evt.get().getLevel()).isEqualTo(Level.INFO);
        assertThat(evt.get().getFormattedMessage()).contains("profile=prod");
    }
}
