package com.pally.api.knowledge;

import com.pally.api.knowledge.dto.WikiCompileResponse;
import com.pally.domain.knowledge.usecase.UploadResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the thin {@link KnowledgeController}: the HTTP-layer concerns it
 * still owns — upload precheck (empty/oversized) and result→status mapping. All
 * business logic + ownership guards are tested in {@link KnowledgeServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeControllerTest {

    @Mock private KnowledgeService knowledgeService;

    @InjectMocks
    private KnowledgeController controller;

    @Test
    void uploadFile_validFile_returns201() {
        MultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "Math content".getBytes());
        when(knowledgeService.uploadFile(anyString(), anyString(), any(MultipartFile.class), anyBoolean()))
                .thenReturn(new UploadResult.Success("file-1", 1, List.of("fractions")));

        var response = controller.uploadFile("user-1", "avatar-1", file, false);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void uploadFile_emptyFile_returns400_andServiceNotCalled() {
        MultipartFile emptyFile = new MockMultipartFile(
                "file", "empty.txt", "text/plain", new byte[0]);

        var response = controller.uploadFile("user-1", "avatar-1", emptyFile, false);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(knowledgeService, never())
                .uploadFile(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void uploadFile_nullFile_returns400() {
        var response = controller.uploadFile("user-1", "avatar-1", null, false);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void uploadFile_oversized_returns413_andServiceNotCalled() {
        byte[] oversized = new byte[26 * 1024 * 1024]; // > 25MB
        MultipartFile bigFile = new MockMultipartFile(
                "file", "big.txt", "text/plain", oversized);

        var response = controller.uploadFile("user-1", "avatar-1", bigFile, false);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        verify(knowledgeService, never())
                .uploadFile(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void uploadFile_relevanceWarning_returns200() {
        MultipartFile file = new MockMultipartFile(
                "file", "recipe.txt", "text/plain", "Pasta recipe".getBytes());
        when(knowledgeService.uploadFile(anyString(), anyString(), any(MultipartFile.class), anyBoolean()))
                .thenReturn(new UploadResult.RelevanceWarning("file-1", 0.15, "Off topic"));

        var response = controller.uploadFile("user-1", "avatar-1", file, false);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void uploadFile_failure_returns500() {
        MultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "content".getBytes());
        when(knowledgeService.uploadFile(anyString(), anyString(), any(MultipartFile.class), anyBoolean()))
                .thenReturn(new UploadResult.Failure("boom"));

        var response = controller.uploadFile("user-1", "avatar-1", file, false);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void compileWiki_async_returns202() {
        when(knowledgeService.compileWiki("user-1", "avatar-1"))
                .thenReturn(new KnowledgeService.CompileOutcome(
                        true, Map.of("compileJobId", "job-1"), null));

        var response = controller.compileWiki("user-1", "avatar-1");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void compileWiki_sync_returns200() {
        when(knowledgeService.compileWiki("user-1", "avatar-1"))
                .thenReturn(new KnowledgeService.CompileOutcome(
                        false, null, new WikiCompileResponse(2, List.of("a", "b"), "done", "haiku")));

        var response = controller.compileWiki("user-1", "avatar-1");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void recompileWiki_returns202() {
        when(knowledgeService.recompileWiki("user-1", "avatar-1"))
                .thenReturn(Map.of("message", "queued"));

        var response = controller.recompileWiki("user-1", "avatar-1");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }
}
