package com.pally.domain.knowledge;

import com.pally.domain.knowledge.dto.KnowledgeFileResponse;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class KnowledgeMapper {

    public KnowledgeFileResponse toResponse(KnowledgeFile file) {
        String ocrEngine = file.getOcrEngine();
        boolean degraded = ocrEngine != null && !ocrEngine.startsWith("claude");
        int extractedChars = file.getExtractedText() != null ? file.getExtractedText().length() : 0;
        return new KnowledgeFileResponse(
                file.getId(),
                file.getFileName(),
                file.getPageCount(),
                file.getStatus(),
                file.getCreatedAt(),
                ocrEngine,
                file.getCompiledBy(),
                degraded,
                extractedChars
        );
    }

    public List<KnowledgeFileResponse> toResponseList(List<KnowledgeFile> files) {
        return files.stream().map(this::toResponse).toList();
    }
}
