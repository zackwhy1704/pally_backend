package com.pally.infrastructure.storage;

import com.pally.domain.knowledge.port.StoragePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.InputStream;

/**
 * AWS S3 implementation of {@link StorageService} and {@link StoragePort}.
 *
 * <p>Active only when the {@code s3} Spring profile is set.
 *
 * <p><strong>TODO:</strong> Replace stub methods with real AWS SDK S3Client calls.
 * The {@link S3Client} is injected but actual put/get/delete operations are not yet wired.
 */
@Service
@ConditionalOnProperty(name = "storage.type", havingValue = "s3")
public class S3StorageService implements StorageService, StoragePort {

    private static final Logger log = LoggerFactory.getLogger(S3StorageService.class);

    private final S3Client s3Client;

    @Value("${storage.s3.bucket-name}")
    private String bucketName;

    @Value("${storage.s3.region:us-east-1}")
    private String region;

    public S3StorageService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    @Override
    public String store(String key, InputStream inputStream, long size, String contentType) {
        // TODO: Integrate AWS SDK S3Client to upload from InputStream
        // Example:
        //   PutObjectRequest request = PutObjectRequest.builder()
        //       .bucket(bucketName)
        //       .key(key)
        //       .contentType(contentType)
        //       .contentLength(size)
        //       .build();
        //   s3Client.putObject(request, RequestBody.fromInputStream(inputStream, size));
        log.warn("S3StorageService.store not fully implemented — TODO: wire AWS SDK call for key={}", key);
        return key;
    }

    @Override
    public String upload(String key, byte[] data, String contentType) {
        // TODO: Integrate AWS SDK S3Client to upload byte array
        // Example:
        //   PutObjectRequest request = PutObjectRequest.builder()
        //       .bucket(bucketName)
        //       .key(key)
        //       .contentType(contentType)
        //       .build();
        //   s3Client.putObject(request, RequestBody.fromBytes(data));
        log.warn("S3StorageService.upload not fully implemented — TODO: wire AWS SDK call for key={}", key);
        return key;
    }

    @Override
    public byte[] download(String key) {
        // TODO: Integrate AWS SDK S3Client to download bytes
        // Example:
        //   GetObjectRequest request = GetObjectRequest.builder()
        //       .bucket(bucketName)
        //       .key(key)
        //       .build();
        //   return s3Client.getObjectAsBytes(request).asByteArray();
        log.warn("S3StorageService.download not fully implemented — TODO: wire AWS SDK call for key={}", key);
        throw new UnsupportedOperationException("S3 download not yet implemented");
    }

    @Override
    public void delete(String key) {
        // TODO: Integrate AWS SDK S3Client to delete object
        // Example:
        //   DeleteObjectRequest request = DeleteObjectRequest.builder()
        //       .bucket(bucketName)
        //       .key(key)
        //       .build();
        //   s3Client.deleteObject(request);
        log.warn("S3StorageService.delete not fully implemented — TODO: wire AWS SDK call for key={}", key);
    }
}
