package com.pally.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** /actuator/info carries the deploy commit + profile (SHA from injected properties). */
class DeployInfoContributorTest {

    @Test
    void addsCommitAndProfile() {
        var env = new MockEnvironment();
        env.setProperty("RAILWAY_GIT_COMMIT_SHA", "abc123def");
        env.setActiveProfiles("prod");

        var b = new Info.Builder();
        new DeployInfoContributor(env).contribute(b);

        var deploy = (Map<?, ?>) b.build().getDetails().get("deploy");
        assertThat(deploy.get("commit")).isEqualTo("abc123def");
        assertThat(deploy.get("profile")).isEqualTo("prod");
    }

    @Test
    void noProfile_showsDefaultMarker_andUnknownShaFallback() {
        var b = new Info.Builder();
        new DeployInfoContributor(new MockEnvironment()).contribute(b);
        var deploy = (Map<?, ?>) b.build().getDetails().get("deploy");
        assertThat(deploy.get("commit")).isEqualTo("unknown");
        assertThat(deploy.get("profile")).isEqualTo("DEFAULT!");
    }
}
