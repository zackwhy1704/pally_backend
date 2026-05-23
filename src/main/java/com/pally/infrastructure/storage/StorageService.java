package com.pally.infrastructure.storage;

import java.io.InputStream;

/**
 * Internal storage service interface used by domain use cases.
 *
 * <p>Provides stream-oriented upload in addition to the byte-array operations
 * exposed by {@link com.pally.domain.knowledge.port.StoragePort}.
 * Concrete implementations also implement {@code StoragePort}.
 */
public interface StorageService {

    /**
     * Stores the content from {@code inputStream} under the given {@code key}.
     *
     * @param key         storage identifier
     * @param inputStream content to store (caller is responsible for closing)
     * @param size        content length in bytes (-1 if unknown)
     * @param contentType MIME type
     * @return resolved storage key
     */
    String store(String key, InputStream inputStream, long size, String contentType);

    /**
     * Deletes the object at the given {@code key}.
     *
     * @param key storage identifier
     */
    void delete(String key);
}
