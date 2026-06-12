package com.pally.infrastructure.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/**
 * Builds the {@link S3Client} bean configured for Cloudflare R2 (S3-compatible).
 *
 * <p>Loaded only when {@code storage.type=s3} AND all {@code R2_*} credentials are
 * present (see {@link R2ConfiguredCondition}). When s3 is selected but the creds are
 * blank (local/CI), this config is skipped, a warning is logged, and
 * {@link LocalStorageService} takes over — so blank R2 vars never crash the app.
 *
 * <p>R2 specifics vs plain AWS S3:
 * <ul>
 *   <li>{@code endpointOverride} points at the account-specific R2 endpoint.</li>
 *   <li>Region is the literal {@code "auto"} — R2 ignores AWS regions.</li>
 *   <li>Path-style access is enabled (R2 does not support virtual-host buckets).</li>
 * </ul>
 */
@Configuration
@Conditional(R2ConfiguredCondition.class)
public class S3ClientConfig {

    @Value("${R2_ENDPOINT:}")
    private String endpoint;

    @Value("${R2_ACCESS_KEY:}")
    private String accessKey;

    @Value("${R2_SECRET_KEY:}")
    private String secretKey;

    @Value("${R2_BUCKET:}")
    private String bucket;

    /// Bucket name used by {@link S3StorageService} for every request.
    public String getBucket() {
        return bucket;
    }

    @Bean
    public S3Client r2S3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .region(Region.of("auto"))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }
}
