package com.pally.api.avatar;

import com.pally.api.avatar.dto.AvatarListResponse;
import com.pally.api.avatar.dto.AvatarResponse;
import com.pally.api.avatar.dto.CreateAvatarRequest;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.shared.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AvatarControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("pally_test")
            .withUsername("pally")
            .withPassword("pally");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("storage.type", () -> "local");
        registry.add("claude.api.key", () -> "test-key");
    }

    @Autowired
    TestRestTemplate restTemplate;

    private static HttpHeaders headersWithUserId(String userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", userId);
        headers.set("Content-Type", "application/json");
        return headers;
    }

    @Test
    void createAvatar_withValidRequest_returns201() {
        var request = new CreateAvatarRequest("Robo", Subject.MATHS, CharacterType.BYTE, null, null);
        var entity = new HttpEntity<>(request, headersWithUserId("user-123"));

        ResponseEntity<ApiResponse<AvatarResponse>> response = restTemplate.exchange(
                "/api/v1/avatars",
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data().name()).isEqualTo("Robo");
        assertThat(response.getBody().data().subject()).isEqualTo(Subject.MATHS);
        assertThat(response.getBody().data().characterType()).isEqualTo(CharacterType.BYTE);
    }

    @Test
    void createAvatar_withBlankName_returns400() {
        var request = new CreateAvatarRequest("", Subject.MATHS, CharacterType.BYTE, null, null);
        var entity = new HttpEntity<>(request, headersWithUserId("user-123"));

        ResponseEntity<ApiResponse<AvatarResponse>> response = restTemplate.exchange(
                "/api/v1/avatars",
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listAvatars_returnsOnlyAvatarsForUser() {
        String userId = "user-list-test";
        var request = new CreateAvatarRequest("Felix", Subject.HISTORY, CharacterType.FINN, null, null);
        restTemplate.exchange("/api/v1/avatars", HttpMethod.POST,
                new HttpEntity<>(request, headersWithUserId(userId)),
                new ParameterizedTypeReference<>() {});

        ResponseEntity<ApiResponse<AvatarListResponse>> listResponse = restTemplate.exchange(
                "/api/v1/avatars",
                HttpMethod.GET,
                new HttpEntity<>(headersWithUserId(userId)),
                new ParameterizedTypeReference<ApiResponse<AvatarListResponse>>() {}
        );

        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody().data().avatars()).isNotEmpty();
        assertThat(listResponse.getBody().data().avatars())
                .allMatch(a -> a.name() != null);
    }

    @Test
    void getAvatar_withUnknownId_returns404() {
        ResponseEntity<ApiResponse<AvatarResponse>> response = restTemplate.exchange(
                "/api/v1/avatars/non-existent-id",
                HttpMethod.GET,
                new HttpEntity<>(headersWithUserId("user-123")),
                new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
