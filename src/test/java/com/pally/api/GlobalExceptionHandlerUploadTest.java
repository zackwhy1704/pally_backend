package com.pally.api;

import com.pally.shared.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A too-large upload fails during multipart PARSING, before the controller's own
 * size check — so it must map to a clean 413, not the generic 500 the user hit
 * uploading a ~19MB book.
 */
class GlobalExceptionHandlerUploadTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void oversizeUpload_mapsTo413_withFriendlyMessage() {
        ResponseEntity<ApiResponse<Void>> resp =
                handler.handleMaxUpload(new MaxUploadSizeExceededException(25L * 1024 * 1024));

        assertThat(resp.getStatusCode().value()).isEqualTo(413);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().status()).isEqualTo(413);
        assertThat(resp.getBody().error()).contains("too large");
    }

    @Test
    void valueTooLong_mapsTo400_withNonBlamingMessage_notCorrupted() {
        // sqlState 22001 = value too long (the varchar overflow on a long bookmark title).
        var ex = new DataIntegrityViolationException(
                "wrap", new SQLException("value too long for varchar(255)", "22001"));

        ResponseEntity<ApiResponse<Void>> resp = handler.handleDataIntegrity(ex);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).isNotNull();
        // Honest, NON-blaming: a write failure is ours to own, never "your file is corrupted".
        assertThat(resp.getBody().error())
                .contains("too long")
                .contains("on us")
                .doesNotContainIgnoringCase("corrupt");
        // Schema detail (column/constraint name) must NOT leak to the client.
        assertThat(resp.getBody().error()).doesNotContain("varchar").doesNotContain("file_name");
    }
}
