package com.pally.infrastructure.config;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Adds a {@code deploy} section to {@code /actuator/info} — the commit SHA + active
 * profile — so "what is prod running" is answerable over HTTP, not just from the boot
 * log. Only safe, non-secret fields (matches build-info's version/time already there).
 */
@Component
public class DeployInfoContributor implements InfoContributor {

    private final Environment env;

    public DeployInfoContributor(Environment env) {
        this.env = env;
    }

    @Override
    public void contribute(Info.Builder builder) {
        final String[] profiles = env.getActiveProfiles();
        builder.withDetail("deploy", Map.of(
                "commit", env.getProperty("RAILWAY_GIT_COMMIT_SHA", "unknown"),
                "profile", profiles.length == 0 ? "DEFAULT!" : String.join(",", profiles)
        ));
    }
}
