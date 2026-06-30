package com.pally.domain.homework;

/**
 * A stored homework artifact: the storage key plus the metadata needed to serve
 * it back with the right content type and display name.
 *
 * @param key         storage key (StoragePort) — never exposed to clients directly
 * @param name        original file name, for display + download
 * @param contentType MIME type, so the serve endpoint streams it correctly
 * @param size        byte size at upload time
 */
public record SubmissionFile(String key, String name, String contentType, long size) {}
