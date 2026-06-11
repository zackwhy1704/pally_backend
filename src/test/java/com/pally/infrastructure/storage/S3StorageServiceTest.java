package com.pally.infrastructure.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link S3StorageService} — verifies the correct AWS-SDK requests
 * are built (bucket, key, content type) for the R2 backend. Uses a mocked
 * {@link S3Client}; never hits a real bucket.
 *
 * <p>Invariants:
 * <ul>
 *   <li>{@code upload} builds a {@code PutObjectRequest} with the configured bucket,
 *       the given key, and the given content type, and returns the key.</li>
 *   <li>{@code store} (stream variant) builds the same with a content length.</li>
 *   <li>{@code download} builds a {@code GetObjectRequest} and returns the object bytes.</li>
 *   <li>{@code delete} builds a {@code DeleteObjectRequest} with bucket + key.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class S3StorageServiceTest {

    private static final String BUCKET = "pally-test-bucket";
    private static final String KEY = "narration/module-1/card_0.mp3";

    @Mock S3Client s3Client;

    private S3StorageService service;

    @BeforeEach
    void setUp() {
        S3ClientConfig config = new S3ClientConfig();
        org.springframework.test.util.ReflectionTestUtils.setField(config, "bucket", BUCKET);
        service = new S3StorageService(s3Client, config);
    }

    @Test
    void upload_buildsPutObjectRequest_withBucketKeyContentType_andReturnsKey() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);

        String result = service.upload(KEY, data, "audio/mpeg");

        assertThat(result).isEqualTo(KEY);
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        PutObjectRequest req = captor.getValue();
        assertThat(req.bucket()).isEqualTo(BUCKET);
        assertThat(req.key()).isEqualTo(KEY);
        assertThat(req.contentType()).isEqualTo("audio/mpeg");
    }

    @Test
    void store_buildsPutObjectRequest_withContentLength_andReturnsKey() {
        byte[] data = "stream-bytes".getBytes(StandardCharsets.UTF_8);

        String result = service.store(
                KEY, new ByteArrayInputStream(data), data.length, "application/pdf");

        assertThat(result).isEqualTo(KEY);
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        PutObjectRequest req = captor.getValue();
        assertThat(req.bucket()).isEqualTo(BUCKET);
        assertThat(req.key()).isEqualTo(KEY);
        assertThat(req.contentType()).isEqualTo("application/pdf");
        assertThat(req.contentLength()).isEqualTo((long) data.length);
    }

    @Test
    void download_buildsGetObjectRequest_andReturnsBytes() {
        byte[] expected = "downloaded".getBytes(StandardCharsets.UTF_8);
        ResponseBytes<GetObjectResponse> responseBytes =
                ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), expected);
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(responseBytes);

        byte[] result = service.download(KEY);

        assertThat(result).isEqualTo(expected);
        ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObjectAsBytes(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(captor.getValue().key()).isEqualTo(KEY);
    }

    @Test
    void delete_buildsDeleteObjectRequest_withBucketAndKey() {
        service.delete(KEY);

        ArgumentCaptor<DeleteObjectRequest> captor =
                ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(captor.getValue().key()).isEqualTo(KEY);
    }
}
