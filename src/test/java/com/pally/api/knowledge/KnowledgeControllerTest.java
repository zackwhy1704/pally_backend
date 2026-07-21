package com.pally.api.knowledge;

import com.pally.domain.consent.ConsentGuard;
import com.pally.domain.knowledge.dto.WikiCompileResponse;
import com.pally.domain.knowledge.KnowledgeService;
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
    @Mock private ConsentGuard consentGuard;

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
    void uploadFile_segmented_returns201_andChunksSurviveSerialization() throws Exception {
        // HTTP-boundary contract pin (was MISSING): a Segmented upload must return the
        // Segmented WHOLE and serialize its `chunks` + `parentFileId` into the JSON body —
        // that is what the web reads to open the chapter picker. The use-case-layer test
        // (UploadChunkRoutingTest) pins only the return TYPE, not serialization; a future
        // mapper change (e.g. a narrowed projection) could silently drop `chunks` and stay
        // green. Fail-without-fix if chunks ever stop reaching JSON.
        MultipartFile file = new MockMultipartFile(
                "file", "big.pdf", "application/pdf", "%PDF-large".getBytes());
        var seg = new UploadResult.Segmented("parent-1", List.of(
                new UploadResult.ChunkInfo("chunk-1", "Chapter 1", 1, 40, 40),
                new UploadResult.ChunkInfo("chunk-2", "Chapter 2", 41, 80, 40)));
        when(knowledgeService.uploadFile(anyString(), anyString(), any(MultipartFile.class), anyBoolean()))
                .thenReturn(seg);

        var response = controller.uploadFile("user-1", "avatar-1", file, false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        // The controller returns the Segmented WHOLE (not a narrowed projection).
        assertThat(response.getBody().data()).isInstanceOf(UploadResult.Segmented.class);
        // And it serializes to JSON carrying chunks + parentFileId + chunkId (the web contract).
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(response.getBody());
        assertThat(json).contains("\"parentFileId\":\"parent-1\"");
        assertThat(json).contains("\"chunks\"");
        assertThat(json).contains("\"chunkId\":\"chunk-1\"");
        assertThat(json).contains("\"title\":\"Chapter 1\"");
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
    void uploadFile_serverFaultFailure_returns500() {
        // A genuine server fault (storage/OCR down) stays 5xx — the client SHOULD retry.
        MultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "content".getBytes());
        when(knowledgeService.uploadFile(anyString(), anyString(), any(MultipartFile.class), anyBoolean()))
                .thenReturn(new UploadResult.Failure("Storage error: boom"));

        var response = controller.uploadFile("user-1", "avatar-1", file, false);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void uploadFile_badInputFailure_returns422_withMessagePreserved() {
        // F1: an unreadable/corrupt/encrypted/empty file is a CONTENT problem — 422,
        // not 500. Fail-without-fix: pre-change every Failure mapped to 500, which
        // told the client "server error, retry" for a file that never processes.
        MultipartFile file = new MockMultipartFile(
                "file", "corrupt.pdf", "application/pdf", "%PDF-broken".getBytes());
        when(knowledgeService.uploadFile(anyString(), anyString(), any(MultipartFile.class), anyBoolean()))
                .thenReturn(UploadResult.Failure.badInput(
                        "Text extraction failed: Missing root object specification in trailer."));

        var response = controller.uploadFile("user-1", "avatar-1", file, false);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error())
                .contains("Text extraction failed");
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
