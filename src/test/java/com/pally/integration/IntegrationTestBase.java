package com.pally.integration;

import com.pally.domain.chat.port.ChatPort;
import com.pally.domain.chat.ChatStreamEvent;
import com.pally.domain.knowledge.RelevanceScore;
import com.pally.domain.knowledge.port.OcrPort;
import com.pally.domain.knowledge.port.RelevancePort;
import com.pally.domain.knowledge.port.WikiCompilerPort;
import com.pally.infrastructure.ai.ClaudeContextAssembler;
import com.pally.infrastructure.ai.ModerationService;
import com.pally.infrastructure.ai.WikiRecompileScheduler;
import com.pally.infrastructure.auth.JwtService;
import com.pally.infrastructure.auth.SocialTokenVerifier;
import com.pally.infrastructure.storage.StorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * Shared base for integration tests. Boots the full Spring context against a
 * Testcontainers PostgreSQL instance and stubs all AI ports so no real API
 * calls are made.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> PG;

    static {
        PG = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("pally_test")
                .withUsername("test")
                .withPassword("test");
        PG.start();
    }

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        // Disable AI consent gate for tests
        registry.add("app.ai-consent.enabled", () -> "false");
        // Use local storage
        registry.add("storage.type", () -> "local");
        registry.add("storage.local.base-path", () -> "/tmp/pally-test");
        // Stripe stubs
        registry.add("stripe.secret-key", () -> "");
        registry.add("stripe.webhook-secret", () -> "");
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected JwtService jwtService;

    // ── AI port stubs — prevents real Claude/Gemini/OCR calls ────────────────

    @MockBean
    protected OcrPort ocrPort;

    @MockBean
    protected WikiCompilerPort wikiCompilerPort;

    @MockBean
    protected ChatPort chatPort;

    @MockBean
    protected RelevancePort relevancePort;

    @MockBean
    protected ModerationService moderationService;

    @MockBean
    protected WikiRecompileScheduler wikiRecompileScheduler;

    @MockBean
    protected StorageService storageService;

    @MockBean
    protected SocialTokenVerifier socialTokenVerifier;

    // ── Test helpers ─────────────────────────────────────────────────────────

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    /**
     * Registers a user via the auth endpoint and returns the JWT token.
     */
    protected AuthResult registerUser(String email, String password) {
        Map<String, String> body = Map.of(
                "email", email,
                "password", password,
                "displayName", "Test User");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(
                baseUrl() + "/api/v1/auth/register", request, Map.class);
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
            return new AuthResult((String) data.get("userId"), (String) data.get("token"));
        }
        // Try to get more detail from the error body
        String errorDetail = response.getBody() != null ? response.getBody().toString() : "null";
        throw new RuntimeException("Registration failed: " + response.getStatusCode()
                + " body=" + errorDetail);
    }

    /**
     * Logs in and returns the JWT token.
     */
    protected AuthResult loginUser(String email, String password) {
        Map<String, String> body = Map.of("email", email, "password", password);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(
                baseUrl() + "/api/v1/auth/login", request, Map.class);
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
            return new AuthResult((String) data.get("userId"), (String) data.get("token"));
        }
        throw new RuntimeException("Login failed: " + response.getStatusCode());
    }

    protected HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    protected <T> ResponseEntity<Map> get(String path, String token) {
        return restTemplate.exchange(
                baseUrl() + path, HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), Map.class);
    }

    protected ResponseEntity<Map> post(String path, String token, Object body) {
        return restTemplate.exchange(
                baseUrl() + path, HttpMethod.POST,
                new HttpEntity<>(body, authHeaders(token)), Map.class);
    }

    protected ResponseEntity<Map> delete(String path, String token) {
        return restTemplate.exchange(
                baseUrl() + path, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(token)), Map.class);
    }

    /**
     * Sets up default lenient stubs for the moderation service so tests
     * that don't care about moderation don't need to configure it.
     */
    protected void stubModerationPassthrough() {
        lenient().when(moderationService.screenInput(anyString(), anyString(), any(), anyString()))
                .thenReturn(new ModerationService.ModerationResult(
                        false, "SAFE", "SAFE", null));
    }

    /**
     * Sets up default lenient stubs for the chat port returning a canned response.
     */
    protected void stubChatResponse(String response) {
        lenient().when(chatPort.streamChat(any(), any(), anyString(), any()))
                .thenReturn(Flux.just(
                        new ChatStreamEvent.Token(response),
                        new ChatStreamEvent.Done(null)));
        lenient().when(chatPort.streamChat(any(), any(), anyString(), any(), anyString()))
                .thenReturn(Flux.just(
                        new ChatStreamEvent.Token(response),
                        new ChatStreamEvent.Done(null)));
    }

    protected void stubRelevanceOnTopic() {
        lenient().when(relevancePort.check(anyString(), anyString(), anyString()))
                .thenReturn(new RelevanceScore(0.9, "On topic"));
    }

    protected void stubRelevanceOffTopic() {
        lenient().when(relevancePort.check(anyString(), anyString(), anyString()))
                .thenReturn(new RelevanceScore(0.1, "Off topic"));
    }

    record AuthResult(String userId, String token) {}
}
