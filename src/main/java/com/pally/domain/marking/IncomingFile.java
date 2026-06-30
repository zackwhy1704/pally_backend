package com.pally.domain.marking;

/**
 * An uploaded marking-reference file as it arrives at the domain boundary — raw
 * bytes plus metadata. The controller maps Spring's {@code MultipartFile} to
 * this so no web/infrastructure type leaks into the domain service.
 *
 * @param name        original file name
 * @param contentType MIME type
 * @param bytes       raw file bytes (read once in the controller)
 */
public record IncomingFile(String name, String contentType, byte[] bytes) {}
