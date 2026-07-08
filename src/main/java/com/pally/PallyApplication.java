package com.pally;

import com.pally.infrastructure.config.BuildInfoLogger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

// Exclude UserDetailsServiceAutoConfiguration: the app authenticates via its own
// JwtAuthenticationFilter chain (SecurityConfig) and has no UserDetailsService, so
// this autoconfig only ever produced a dev "generated security password" — which must
// never appear in ANY environment's logs. Verified SecurityConfig doesn't rely on it.
@SpringBootApplication(exclude = { UserDetailsServiceAutoConfiguration.class })
@EnableAsync
@EnableScheduling
public class PallyApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(PallyApplication.class);
        // Registered here (not @Component) so it can log build+profile as one of the
        // first lines after the banner, before bean initialisation.
        app.addListeners(new BuildInfoLogger());
        app.run(args);
    }
}
