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
import com.pally.domain.knowledge.port.StoragePort;
import com.pally.infrastructure.storage.StorageService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.transaction.annotation.Transactional;
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

    private static final Logger log = LoggerFactory.getLogger(IntegrationTestBase.class);

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
        // AI consent gate is now always-on; tests grant consent via grantAiConsent().
        // Use local storage (R2 creds absent in CI).
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
    protected StoragePort storagePort;

    @MockBean
    protected SocialTokenVerifier socialTokenVerifier;

    @MockBean
    protected com.pally.infrastructure.push.FcmService fcmService;

    @MockBean
    protected com.pally.infrastructure.email.EmailService emailService;

    @MockBean
    protected com.pally.domain.notification.WeeklyEmailScheduler weeklyEmailScheduler;

    @MockBean
    protected com.pally.domain.notification.MilestoneNotifier milestoneNotifier;

    @MockBean
    protected com.pally.domain.notification.RiskAlertScheduler riskAlertScheduler;

    @PersistenceContext
    protected EntityManager entityManager;

    // ── Test data cleanup ────────────────────────────────────────────────────

    /**
     * Deletes every user whose email is in the test-only {@code @test.com}
     * domain after each test, plus their owned data, so stale users don't
     * accumulate in a shared (static) Testcontainers Postgres across the suite.
     *
     * <p>{@code @test.com} is a reserved test domain — production users never
     * use it — so this is safe even though the container is shared.
     *
     * <p>NOT a {@code @Transactional}-rollback strategy: these are
     * {@code RANDOM_PORT} HTTP tests whose writes happen on the server thread
     * in their own transactions, so a test-method rollback would not undo them.
     *
     * <p>Deletion order matters because {@code avatars.user_id} has NO foreign
     * key (so a user delete does NOT cascade to avatars), and a few tables
     * ({@code star_award_log}) hold NOT-NULL non-cascading FKs to users:
     * <ol>
     *   <li>delete the test users' avatars — this CASCADES to wiki_pages,
     *       knowledge_files, chat, flashcards, etc. (all FK ON DELETE CASCADE
     *       from avatars);</li>
     *   <li>delete star_award_log rows referencing the test users (NOT-NULL,
     *       non-cascading FK on parent_id/child_id);</li>
     *   <li>delete the users themselves — remaining child tables cascade.</li>
     * </ol>
     *
     * Best-effort: any failure is logged, never fails the test.
     */
    @AfterEach
    @Transactional
    void cleanupTestData() {
        try {
            // 1. Avatars owned by test users (cascade-deletes their wiki/chat/etc.)
            entityManager.createNativeQuery(
                    "DELETE FROM avatars WHERE user_id IN "
                    + "(SELECT id FROM users WHERE email LIKE '%@test.com')")
                    .executeUpdate();
            // 2. Non-cascading NOT-NULL FK rows to users.
            entityManager.createNativeQuery(
                    "DELETE FROM star_award_log WHERE parent_id IN "
                    + "(SELECT id FROM users WHERE email LIKE '%@test.com') "
                    + "OR child_id IN (SELECT id FROM users WHERE email LIKE '%@test.com')")
                    .executeUpdate();
            // 2b. Consent proof no longer cascades from users (V119 dropped the FK so real
            // purges RETAIN it). For TEST hygiene only, clear it for @test.com users so the
            // shared container doesn't accumulate across the suite.
            entityManager.createNativeQuery(
                    "DELETE FROM consent_records WHERE user_id IN "
                    + "(SELECT id FROM users WHERE email LIKE '%@test.com')").executeUpdate();
            entityManager.createNativeQuery(
                    "DELETE FROM consent_requests WHERE child_user_id IN "
                    + "(SELECT id FROM users WHERE email LIKE '%@test.com')").executeUpdate();
            // 3. The test users themselves (remaining children cascade).
            int users = entityManager.createNativeQuery(
                    "DELETE FROM users WHERE email LIKE '%@test.com'")
                    .executeUpdate();
            if (users > 0) {
                log.debug("[Cleanup] Deleted {} @test.com user(s) after test", users);
            }
        } catch (Exception e) {
            // Never let cleanup break the suite — a missing optional table or a
            // schema-version skew should only log, not fail the test.
            log.warn("[Cleanup] Test-data cleanup failed (non-fatal): {}", e.getMessage());
        }
    }

    // ── Test helpers ─────────────────────────────────────────────────────────

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    /**
     * Registers a user via the auth endpoint and returns the JWT token.
     */
    protected AuthResult registerUser(String email, String password) {
        // birthYear is REQUIRED for a student account (age-gate fail-safe). Seed a 13+
        // year so the default fixture is an ordinary adult-consent account.
        Map<String, Object> body = Map.of(
                "email", email,
                "password", password,
                "displayName", "Test User",
                "birthYear", java.time.Year.now(java.time.ZoneId.of("Asia/Singapore")).getValue() - 20,
                "acceptedTerms", true);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
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
     * Registers a STUDENT user with an explicit birth year (PDPC age gate tests).
     * A low birth year (e.g. current year - 10) makes the user under-13.
     */
    protected AuthResult registerUserWithBirthYear(String email, String password, int birthYear) {
        Map<String, Object> body = Map.of(
                "email", email,
                "password", password,
                "displayName", "Test User",
                "birthYear", birthYear,
                "acceptedTerms", true);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(
                baseUrl() + "/api/v1/auth/register", request, Map.class);
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
            return new AuthResult((String) data.get("userId"), (String) data.get("token"));
        }
        String errorDetail = response.getBody() != null ? response.getBody().toString() : "null";
        throw new RuntimeException("Registration failed: " + response.getStatusCode()
                + " body=" + errorDetail);
    }

    /**
     * Registers an UNDER-13 student with a mandatory parent email — the account is
     * created PENDING_PARENTAL_CONSENT (blocked from new-child-data until approved).
     */
    protected AuthResult registerUnder13(String email, String password, int birthYear, String parentEmail) {
        Map<String, Object> body = Map.of(
                "email", email, "password", password, "displayName", "Kid",
                "birthYear", birthYear, "parentEmail", parentEmail,
                "acceptedTerms", true);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = restTemplate.postForEntity(
                baseUrl() + "/api/v1/auth/register", new HttpEntity<>(body, headers), Map.class);
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
            return new AuthResult((String) data.get("userId"), (String) data.get("token"));
        }
        throw new RuntimeException("Under-13 registration failed: " + response.getStatusCode()
                + " body=" + (response.getBody() != null ? response.getBody().toString() : "null"));
    }

    /**
     * Grants the always-on third-party AI data-transfer consent for a user so
     * that chat / upload paths are not blocked by the AI-disclosure gate. Mirrors
     * the real client flow: POST /api/v1/consent/ai-data-transfer.
     */
    protected void grantAiConsent(String token) {
        ResponseEntity<Map> response = restTemplate.exchange(
                baseUrl() + "/api/v1/consent/ai-data-transfer", HttpMethod.POST,
                new HttpEntity<>(authHeaders(token)), Map.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("grantAiConsent failed: " + response.getStatusCode()
                    + " body=" + (response.getBody() != null ? response.getBody().toString() : "null"));
        }
    }

    /**
     * Registers a 13+ user AND grants AI consent in one step — the common setup
     * for any test that exercises chat or upload happy paths.
     */
    protected AuthResult registerConsentedUser(String email, String password) {
        // Establish a 13+ birth year so the default-deny child-data gate is a no-op for
        // the common upload/chat happy-path users (an unknown age is now denied).
        int teenYear = java.time.Year.now(java.time.ZoneId.of("Asia/Singapore")).getValue() - 18;
        AuthResult auth = registerUserWithBirthYear(email, password, teenYear);
        grantAiConsent(auth.token());
        return auth;
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
        lenient().when(moderationService.screenInput(anyString(), anyString(), any(), anyString(), anyString()))
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
