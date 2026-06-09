package com.pally.api.knowledge;

import com.pally.api.knowledge.dto.KnowledgeFileResponse;
import com.pally.domain.knowledge.KnowledgeFile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps {@link KnowledgeFile} domain objects to API response DTOs.
 */
@Component
public class KnowledgeMapper {

    /**
     * Maps a single {@link KnowledgeFile} to a {@link KnowledgeFileResponse}.
     *
     * @param file domain knowledge file
     * @return response DTO
     */
    public KnowledgeFileResponse toResponse(KnowledgeFile file) {
        String ocrEngine = file.getOcrEngine();
        boolean degraded = ocrEngine != null && !ocrEngine.startsWith("claude");
        return new KnowledgeFileResponse(
                file.getId(),
                file.getFileName(),
                file.getPageCount(),
                file.getStatus(),
                file.getCreatedAt(),
                ocrEngine,
                file.getCompiledBy(),
                degraded
        );
    }

    /**
     * Maps a list of {@link KnowledgeFile} objects to response DTOs.
     *
     * @param files list of domain knowledge files
     * @return list of response DTOs
     */
    public List<KnowledgeFileResponse> toResponseList(List<KnowledgeFile> files) {
        return files.stream().map(this::toResponse).toList();
    }
}
