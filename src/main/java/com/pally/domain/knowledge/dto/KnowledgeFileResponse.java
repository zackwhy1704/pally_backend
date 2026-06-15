package com.pally.domain.knowledge.dto;

import com.pally.domain.knowledge.KnowledgeFile;

import java.time.Instant;

public record KnowledgeFileResponse(
        String id,
        String fileName,
        int pageCount,
        KnowledgeFile.Status status,
        Instant createdAt,
        String ocrEngine,
        String compiledBy,
        boolean degraded
) {}
