package com.pally.domain.marking;

/**
 * A marking-reference artifact loaded for streaming back to a teacher — the raw
 * bytes plus the content type and name needed to serve it.
 *
 * @param bytes       raw file bytes
 * @param contentType MIME type
 * @param name        display name
 */
public record LoadedArtifact(byte[] bytes, String contentType, String name) {}
