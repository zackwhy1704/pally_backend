package com.pally.domain.marking;

/**
 * A stored marking-reference artifact: the storage key plus the metadata needed
 * to serve it back with the right content type and display name. Mirrors the
 * homework {@code SubmissionFile} — the marking context owns its own boundary
 * type rather than reaching into another bounded context.
 *
 * @param key         storage key (StoragePort) — never exposed to clients directly
 * @param name        original file name, for display + download
 * @param contentType MIME type, so the serve endpoint streams it correctly
 * @param size        byte size at upload time
 */
public record MarkingReferenceFile(String key, String name, String contentType, long size) {}
