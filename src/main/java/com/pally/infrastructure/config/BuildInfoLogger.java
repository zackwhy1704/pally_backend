package com.pally.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

/**
 * Logs — as one of the FIRST lines after the banner — exactly what this process is:
 * <pre>[Build] commit=&lt;sha&gt; built=&lt;time&gt; profile=&lt;active|DEFAULT!&gt;</pre>
 * so a stale deploy or a missing Spring profile announces itself instead of being
 * inferred later from migration counts and zombie logs (the incident this fixes).
 *
 * <p>The commit SHA is read from {@code RAILWAY_GIT_COMMIT_SHA} (Railway sets it at
 * build+runtime) so no {@code .git} is needed at build time. Build time comes from
 * the generated {@code META-INF/build-info.properties}. Registered manually in
 * {@link com.pally.PallyApplication#main} so it can run this early; ordered to run
 * AFTER Spring Boot's LoggingApplicationListener so the log system is initialised.
 */
public class BuildInfoLogger
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    private static final Logger log = LoggerFactory.getLogger(BuildInfoLogger.class);

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        final var env = event.getEnvironment();
        final String sha = env.getProperty("RAILWAY_GIT_COMMIT_SHA", "unknown");
        final String built = buildTime();
        final String[] profiles = env.getActiveProfiles();

        if (profiles.length == 0) {
            // LOUD: no profile → SecretsValidator would skip strict checks and Spring
            // may autoconfigure a dev security password. This must never pass unnoticed.
            log.warn("[Build] commit={} built={} profile=DEFAULT! "
                    + "(no SPRING_PROFILES_ACTIVE — set it; dev security autoconfig may be active)",
                    sha, built);
        } else {
            log.info("[Build] commit={} built={} profile={}", sha, built, String.join(",", profiles));
        }
    }

    private String buildTime() {
        try {
            var res = new ClassPathResource("META-INF/build-info.properties");
            if (!res.exists()) return "unknown";
            var props = new Properties();
            try (var in = res.getInputStream()) {
                props.load(in);
            }
            return props.getProperty("build.time", "unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Override
    public int getOrder() {
        // After LoggingApplicationListener (HIGHEST_PRECEDENCE + 20) so logging is ready.
        return Ordered.HIGHEST_PRECEDENCE + 21;
    }
}
