package com.pally.infrastructure.storage;

import com.pally.domain.knowledge.port.StoragePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;

/**
 * Cloudflare R2 (S3-compatible) implementation of {@link StorageService} and
 * {@link StoragePort}.
 *
 * <p>Active only when {@code storage.type=s3}. {@link LocalStorageService} stays the
 * default ({@code matchIfMissing=true}). The {@link S3Client} bean is built for R2 by
 * {@link S3ClientConfig}, which reads the {@code R2_*} environment variables.
 *
 * <p>Returns the storage <em>key</em> from {@link #store} / {@link #upload} (never a
 * full URL) to match {@link LocalStorageService}'s contract — {@code NarrationService}
 * stores that value as {@code audioUrl} and downstream code re-derives access via the
 * key, so both backends behave identically.
 */
@Service
@ConditionalOnProperty(name = "storage.type", havingValue = "s3")
public class S3StorageService implements StorageService, StoragePort {

    private static final Logger log = LoggerFactory.getLogger(S3StorageService.class);

    private final S3Client s3Client;
    private final String bucket;

    public S3StorageService(S3Client s3Client, S3ClientConfig config) {
        this.s3Client = s3Client;
        this.bucket = config.getBucket();
    }

    @Override
    public String store(String key, InputStream inputStream, long size, String contentType) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .contentLength(size)
                .build();
        s3Client.putObject(request, RequestBody.fromInputStream(inputStream, size));
        log.debug("[R2] Stored key={} bucket={} size={}", key, bucket, size);
        return key;
    }

    @Override
    public String upload(String key, byte[] data, String contentType) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build();
        s3Client.putObject(request, RequestBody.fromBytes(data));
        log.debug("[R2] Uploaded key={} bucket={} bytes={}", key, bucket, data.length);
        return key;
    }

    @Override
    public byte[] download(String key) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        byte[] bytes = s3Client.getObjectAsBytes(request).asByteArray();
        log.debug("[R2] Downloaded key={} bucket={} bytes={}", key, bucket, bytes.length);
        return bytes;
    }

    @Override
    public void delete(String key) {
        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        s3Client.deleteObject(request);
        log.debug("[R2] Deleted key={} bucket={}", key, bucket);
    }
}
