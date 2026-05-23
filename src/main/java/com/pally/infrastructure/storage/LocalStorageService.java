package com.pally.infrastructure.storage;

import com.pally.domain.knowledge.port.StoragePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Local filesystem implementation of {@link StorageService} and {@link StoragePort}.
 *
 * <p>Active only when the {@code local} Spring profile is set.
 * Files are stored under the directory configured by {@code storage.local.base-path}.
 */
@Service
@ConditionalOnProperty(name = "storage.type", havingValue = "local", matchIfMissing = true)
public class LocalStorageService implements StorageService, StoragePort {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageService.class);

    @Value("${storage.local.base-path:./data/storage}")
    private String basePath;

    @Override
    public String store(String key, InputStream inputStream, long size, String contentType) {
        Path target = resolvePath(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Stored file key={} path={}", key, target);
            return key;
        } catch (IOException e) {
            log.error("Failed to store file key={}", key, e);
            throw new RuntimeException("Local storage write failed for key: " + key, e);
        }
    }

    @Override
    public String upload(String key, byte[] data, String contentType) {
        Path target = resolvePath(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, data);
            log.debug("Uploaded file key={} bytes={}", key, data.length);
            return key;
        } catch (IOException e) {
            log.error("Failed to upload file key={}", key, e);
            throw new RuntimeException("Local storage upload failed for key: " + key, e);
        }
    }

    @Override
    public byte[] download(String key) {
        Path source = resolvePath(key);
        try {
            return Files.readAllBytes(source);
        } catch (IOException e) {
            log.error("Failed to download file key={}", key, e);
            throw new RuntimeException("Local storage read failed for key: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        Path target = resolvePath(key);
        try {
            Files.deleteIfExists(target);
            log.debug("Deleted file key={}", key);
        } catch (IOException e) {
            log.error("Failed to delete file key={}", key, e);
            throw new RuntimeException("Local storage delete failed for key: " + key, e);
        }
    }

    private Path resolvePath(String key) {
        // Sanitise key to prevent path traversal
        String safeKey = key.replaceAll("\\.\\.", "").replaceAll("^/+", "");
        return Paths.get(basePath, safeKey).normalize();
    }
}
