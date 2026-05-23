package com.pally.domain.knowledge.port;

/**
 * Port for binary file storage operations.
 */
public interface StoragePort {

    /**
     * Uploads bytes to the storage backend under the given key.
     *
     * @param key         unique storage identifier (e.g. "avatars/abc/uploads/file.pdf")
     * @param data        raw file bytes
     * @param contentType MIME type of the data
     * @return the storage key (may be the same as input or a resolved URL depending on impl)
     */
    String upload(String key, byte[] data, String contentType);

    /**
     * Downloads and returns the raw bytes for the given key.
     *
     * @param key storage identifier
     * @return file bytes
     */
    byte[] download(String key);

    /**
     * Deletes the object at the given key.
     *
     * @param key storage identifier
     */
    void delete(String key);
}
