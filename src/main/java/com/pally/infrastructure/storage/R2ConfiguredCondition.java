package com.pally.infrastructure.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * True only when {@code storage.type=s3} AND every required Cloudflare R2 credential
 * ({@code R2_ENDPOINT}, {@code R2_ACCESS_KEY}, {@code R2_SECRET_KEY}, {@code R2_BUCKET})
 * is present and non-blank.
 *
 * <p>This guards the R2 {@code S3Client} bean and {@link S3StorageService}. Production
 * (Railway) has the creds set, so R2 is the default there. Local dev / CI either set
 * {@code STORAGE_TYPE=local} (tests force this) or simply leave the R2 vars blank — in
 * which case this condition is false, the S3 beans are skipped, a clear warning is
 * logged, and {@link LocalStorageService} takes over as the fallback. The app always
 * boots; it never crashes on missing R2 creds.
 */
public class R2ConfiguredCondition implements Condition {

    private static final Logger log = LoggerFactory.getLogger(R2ConfiguredCondition.class);

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();

        String type = env.getProperty("storage.type", "s3");
        if (!"s3".equalsIgnoreCase(type)) {
            return false; // not using s3 — LocalStorageService handles it
        }

        boolean configured = isR2Configured(env);
        if (!configured) {
            log.warn("[Storage] storage.type=s3 but R2 credentials are missing/blank "
                    + "(R2_ENDPOINT/R2_ACCESS_KEY/R2_SECRET_KEY/R2_BUCKET). "
                    + "Skipping R2 S3Client — falling back to LOCAL storage. "
                    + "Set the R2_* env vars to enable Cloudflare R2.");
        }
        return configured;
    }

    /// Pure check (no logging) so the complementary {@link LocalStorageActiveCondition}
    /// can reuse it without emitting the warning twice.
    static boolean isR2SelectedAndConfigured(Environment env) {
        String type = env.getProperty("storage.type", "s3");
        return "s3".equalsIgnoreCase(type) && isR2Configured(env);
    }

    private static boolean isR2Configured(Environment env) {
        return present(env, "R2_ENDPOINT")
                && present(env, "R2_ACCESS_KEY")
                && present(env, "R2_SECRET_KEY")
                && present(env, "R2_BUCKET");
    }

    private static boolean present(Environment env, String key) {
        String v = env.getProperty(key);
        return v != null && !v.isBlank();
    }
}
