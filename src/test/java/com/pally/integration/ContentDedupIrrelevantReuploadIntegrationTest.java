package com.pally.integration;

import com.pally.domain.knowledge.ContentDeduplicator;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.infrastructure.persistence.knowledge.KnowledgeFileJpaEntity;
import com.pally.infrastructure.persistence.knowledge.KnowledgeFileJpaRepository;
import com.pally.shared.exception.DuplicateContentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-DB pin for the dedup query bug: the exact-hash presence check must exclude
 * IRRELEVANT (subject-mismatch) files, not just FAILED. Before the fix the query was
 * {@code ...AndStatusNot(FAILED)}, so re-uploading the same content after a relevance
 * rejection (e.g. with skipRelevance) threw a 409 DuplicateContentException — a dead end.
 *
 * <p>This runs through the REAL derived query against Testcontainers Postgres (the unit
 * test can only mock the boolean), so it fails without the StatusIn fix and passes with it.
 */
class ContentDedupIrrelevantReuploadIntegrationTest extends IntegrationTestBase {

    @Autowired ContentDeduplicator deduplicator;
    @Autowired KnowledgeFileJpaRepository fileRepo;

    private static final String TEXT =
            "the sales game is a book about negotiation tactics closing techniques "
          + "objection handling rapport building and prospecting for business deals";

    private String avatarId;
    private String userId;

    @BeforeEach
    void seedAvatar() {
        // A real avatar row is required (knowledge_files.avatar_id is a NOT NULL FK).
        AuthResult auth = registerConsentedUser(
                "dedup-it-" + System.nanoTime() + "@test.com", "password123");
        userId = auth.userId();
        ResponseEntity<Map> createResp = post("/api/v1/avatars", auth.token(),
                Map.of("name", "SalesBot", "subject", "GENERAL", "characterType", "MOCHI"));
        assertThat(createResp.getStatusCode().is2xxSuccessful())
                .as("avatar create: %s / %s", createResp.getStatusCode(), createResp.getBody())
                .isTrue();
        avatarId = (String) ((Map<String, Object>) createResp.getBody().get("data")).get("id");
    }

    private void persist(String id, KnowledgeFile.Status status) {
        KnowledgeFile kf = KnowledgeFile.reconstitute(
                id, avatarId, userId, "sales_game.pdf", "key-" + id, 3,
                KnowledgeFile.UploadType.PDF, status, Instant.now(), TEXT);
        kf.setContentHash(deduplicator.computeHash(TEXT));
        fileRepo.save(KnowledgeFileJpaEntity.fromDomain(kf));
    }

    @Test
    void reuploadOfIrrelevantContent_isAllowed_notBlockedAs409() {
        // Prior upload was rejected as IRRELEVANT (extracted fine, wrong subject).
        persist("f-irrelevant-" + System.nanoTime(), KnowledgeFile.Status.IRRELEVANT);

        // Re-upload of the SAME content (the "Add Anyway" / skipRelevance path) must pass
        // the dedup gate — an IRRELEVANT file does not count as present in the brain.
        assertThatCode(() -> deduplicator.check(avatarId, TEXT, "reupload.pdf"))
                .doesNotThrowAnyException();
    }

    @Test
    void reuploadOfFailedContent_isAlsoAllowed() {
        persist("f-failed-" + System.nanoTime(), KnowledgeFile.Status.FAILED);

        assertThatCode(() -> deduplicator.check(avatarId, TEXT, "reupload.pdf"))
                .doesNotThrowAnyException();
    }

    @Test
    void reuploadOfReadyContent_isStillBlocked_dedupStillWorks() {
        // A genuinely PRESENT (READY) file with the same content must still be caught —
        // the fix must not disable dedup, only stop counting rejected files as present.
        persist("f-ready-" + System.nanoTime(), KnowledgeFile.Status.READY);

        assertThatThrownBy(() -> deduplicator.check(avatarId, TEXT, "reupload.pdf"))
                .isInstanceOf(DuplicateContentException.class);
    }
}
