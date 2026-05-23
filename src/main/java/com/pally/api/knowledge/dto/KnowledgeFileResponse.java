package com.pally.api.knowledge.dto;

import com.pally.domain.knowledge.KnowledgeFile;

import java.time.Instant;

/**
 * Response body for a single knowledge file.
 *
 * @param id        unique file identifier
 * @param fileName  original uploaded file name
 * @param pageCount number of pages detected (0 while processing)
 * @param status    current processing status
 * @param createdAt upload timestamp
 */
public record KnowledgeFileResponse(
        String id,
        String fileName,
        int pageCount,
        KnowledgeFile.Status status,
        Instant createdAt
) {}
