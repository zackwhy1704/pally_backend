package com.pally.domain.homework;

/**
 * A submission artifact loaded from storage, ready to stream back to a client.
 *
 * @param bytes       raw file bytes
 * @param contentType MIME type for the response
 * @param name        original file name (for the download filename)
 */
public record LoadedArtifact(byte[] bytes, String contentType, String name) {}
