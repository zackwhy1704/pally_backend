package com.pally.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Executes the mastery-audit read-model end-to-end — real HTTP, real Spring security
 * filter, real JPA, real PostgreSQL (Testcontainers) — against the EXACT row shape of
 * a real production module.
 *
 * <p><b>Provenance of the fixture.</b> These are not invented numbers. They replicate
 * production module {@code 1dd0ab01-…} / user {@code 4489c513-…}
 * ("Indirect Prospecting: They Prospect You!"), read from the live Railway Postgres
 * on 2026-08-21:
 * <pre>
 *   stage=LEARN signal=null           score=NOT NULL  x6
 *   stage=TEST  signal=DETERMINISTIC  score=NOT NULL  x3
 *   stage=TEST  signal=UNGRADED       score=NULL      x4
 *   stage=PROVE signal=SELF_REPORT    score=NOT NULL  x5
 *   learning_module.mastery_pct = 20.00
 * </pre>
 * A direct SQL aggregation over that production row set expects: evidenceCount=18,
 * masteryContributingCount=8, and per-tier mass DETERMINISTIC=3.0, SELF_REPORT=1.5,
 * LEGACY_UNTYPED=0.0, UNGRADED=0.0. This test asserts the endpoint reproduces exactly
 * that — so a divergence between the SQL truth and what the API reports fails here
 * rather than being discovered in a demo.
 *
 * <p>The prod row set is a genuinely useful case: 18 pieces of evidence, of which only
 * 8 actually moved mastery. Reporting evidenceCount alone would overstate the basis of
 * the number by more than 2x.
 */
class MasteryAuditEndpointIT extends IntegrationTestBase {

    // JdbcTemplate (not EntityManager): these are RANDOM_PORT HTTP tests, so seed
    // rows must be COMMITTED to be visible to the server thread's own transaction.
    @Autowired
    private JdbcTemplate jdbc;

    /** Mirrors the production row shape documented above. */
    private record ProdRow(String stage, String signalType, BigDecimal score) {}

    private static final List<ProdRow> PRODUCTION_ROW_SHAPE = List.of(
            new ProdRow("LEARN", null, BigDecimal.ONE),
            new ProdRow("LEARN", null, BigDecimal.ONE),
            new ProdRow("LEARN", null, BigDecimal.ONE),
            new ProdRow("LEARN", null, BigDecimal.ONE),
            new ProdRow("LEARN", null, BigDecimal.ONE),
            new ProdRow("LEARN", null, BigDecimal.ONE),
            new ProdRow("TEST", "DETERMINISTIC", BigDecimal.ONE),
            new ProdRow("TEST", "DETERMINISTIC", BigDecimal.ZERO),
            new ProdRow("TEST", "DETERMINISTIC", BigDecimal.ZERO),
            new ProdRow("TEST", "UNGRADED", null),
            new ProdRow("TEST", "UNGRADED", null),
            new ProdRow("TEST", "UNGRADED", null),
            new ProdRow("TEST", "UNGRADED", null),
            new ProdRow("PROVE", "SELF_REPORT", new BigDecimal("0.5")),
            new ProdRow("PROVE", "SELF_REPORT", new BigDecimal("0.5")),
            new ProdRow("PROVE", "SELF_REPORT", new BigDecimal("0.5")),
            new ProdRow("PROVE", "SELF_REPORT", new BigDecimal("0.0")),
            new ProdRow("PROVE", "SELF_REPORT", new BigDecimal("0.0")));

    private static final Instant LAST_EVIDENCE_AT = Instant.parse("2026-07-15T16:26:13.269537Z");

    @SuppressWarnings("unchecked")
    private Map<String, Object> tier(Map<String, Object> body, String name) {
        List<Map<String, Object>> breakdown =
                (List<Map<String, Object>>) ((Map<String, Object>) body.get("data")).get("trustBreakdown");
        return breakdown.stream()
                .filter(t -> name.equals(t.get("tier")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("tier missing: " + name));
    }

    private double dbl(Object o) {
        return ((Number) o).doubleValue();
    }

    @SuppressWarnings("unchecked")
    @Test
    void auditEndpoint_reproducesTheProductionSqlAggregation_overRealPostgres() {
        AuthResult user = registerUser("mastery-audit-" + UUID.randomUUID() + "@test.com", "Password123!");

        String avatarId = UUID.randomUUID().toString();
        String moduleId = UUID.randomUUID().toString();

        jdbc.update("INSERT INTO avatars (id, user_id, name, subject, character_type, wiki_page_count, created_at) "
                + "VALUES (?, ?, 'Audit Fixture', 'GENERAL', 'MOCHI', 0, now())",
                avatarId, user.userId());

        jdbc.update("INSERT INTO learning_module (id, avatar_id, wiki_page_slug, title, stage, tier, mastery_pct, created_at) "
                + "VALUES (?, ?, 'indirect-prospecting', 'Indirect Prospecting: They Prospect You!', "
                + "'PROVE', 'CENTRE', 20.00, now())",
                moduleId, avatarId);

        // module_progress.item_id carries a real FK to module_content_item, so each
        // evidence row needs its parent item. (Only real Postgres enforces this — the
        // mocked-repository unit tests could not have surfaced it.)
        int sortOrder = 0;
        for (ProdRow r : PRODUCTION_ROW_SHAPE) {
            String itemId = UUID.randomUUID().toString();
            String type = switch (r.stage()) {
                case "LEARN" -> "MICRO_CARD";
                case "TEST" -> "HOT_TAKE";
                default -> "PROVE_QUESTION";
            };
            jdbc.update("INSERT INTO module_content_item (id, module_id, stage, type, content_json, "
                    + "answer_json, sort_order, tier_required, created_at, status, reap_attempts) "
                    + "VALUES (?, ?, ?, ?, '{}', '{}', ?, 'FREE', now(), 'LIVE', 0)",
                    itemId, moduleId, r.stage(), type, sortOrder++);
            jdbc.update("INSERT INTO module_progress (id, module_id, user_id, item_id, stage, score, signal_type, completed_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID().toString(), moduleId, user.userId(),
                    itemId, r.stage(), r.score(), r.signalType(),
                    java.sql.Timestamp.from(LAST_EVIDENCE_AT));
        }

        ResponseEntity<Map> response = restTemplate.exchange(
                baseUrl() + "/api/v1/avatars/" + avatarId + "/modules/" + moduleId + "/mastery-audit",
                HttpMethod.GET, new HttpEntity<>(authHeaders(user.token())), Map.class);

        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("endpoint must answer over real HTTP: %s", response.getBody())
                .isTrue();

        Map<String, Object> body = response.getBody();
        Map<String, Object> data = (Map<String, Object>) body.get("data");

        // ── Reconciliation against the production SQL aggregation ────────────
        assertThat(data.get("moduleId")).isEqualTo(moduleId);
        assertThat(dbl(data.get("masteryPct"))).isEqualTo(20.00, within(1e-9));
        assertThat((Integer) data.get("evidenceCount"))
                .as("SQL: SELECT COUNT(*) -> 18")
                .isEqualTo(18);
        assertThat((Integer) data.get("masteryContributingCount"))
                .as("SQL: contributesToMastery predicate -> 8, NOT 18")
                .isEqualTo(8);

        assertThat((Integer) tier(body, "DETERMINISTIC").get("count")).isEqualTo(3);
        assertThat((Integer) tier(body, "DETERMINISTIC").get("contributingCount")).isEqualTo(3);
        assertThat(dbl(tier(body, "DETERMINISTIC").get("weightedContribution")))
                .isEqualTo(3.0, within(1e-9));

        assertThat((Integer) tier(body, "SELF_REPORT").get("count")).isEqualTo(5);
        assertThat((Integer) tier(body, "SELF_REPORT").get("contributingCount")).isEqualTo(5);
        assertThat(dbl(tier(body, "SELF_REPORT").get("weight"))).isEqualTo(0.30, within(1e-9));
        assertThat(dbl(tier(body, "SELF_REPORT").get("weightedContribution")))
                .as("5 self-reports carry the evidence mass of 1.5 verified answers")
                .isEqualTo(1.5, within(1e-9));

        // 6 LEARN rows carry a null signal_type: visible as evidence, but LEARN is a
        // completion marker and never feeds mastery.
        assertThat((Integer) tier(body, "LEGACY_UNTYPED").get("count")).isEqualTo(6);
        assertThat((Integer) tier(body, "LEGACY_UNTYPED").get("contributingCount")).isZero();
        assertThat(dbl(tier(body, "LEGACY_UNTYPED").get("weightedContribution")))
                .isEqualTo(0.0, within(1e-9));

        assertThat((Integer) tier(body, "UNGRADED").get("count")).isEqualTo(4);
        assertThat((Integer) tier(body, "UNGRADED").get("contributingCount")).isZero();
        assertThat(dbl(tier(body, "UNGRADED").get("weightedContribution")))
                .isEqualTo(0.0, within(1e-9));

        // The product claim, over real data: verified evidence outweighs the LARGER
        // volume of self-reported evidence (3.0 > 1.5 despite 3 rows vs 5).
        assertThat(dbl(tier(body, "DETERMINISTIC").get("weightedContribution")))
                .isGreaterThan(dbl(tier(body, "SELF_REPORT").get("weightedContribution")));
    }

    @Test
    void auditEndpoint_rejectsAnotherUsersModule_with404() {
        AuthResult owner = registerUser("audit-owner-" + UUID.randomUUID() + "@test.com", "Password123!");
        AuthResult attacker = registerUser("audit-attacker-" + UUID.randomUUID() + "@test.com", "Password123!");

        String avatarId = UUID.randomUUID().toString();
        String moduleId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO avatars (id, user_id, name, subject, character_type, wiki_page_count, created_at) "
                + "VALUES (?, ?, 'Owned', 'GENERAL', 'MOCHI', 0, now())", avatarId, owner.userId());
        jdbc.update("INSERT INTO learning_module (id, avatar_id, wiki_page_slug, title, stage, tier, mastery_pct, created_at) "
                + "VALUES (?, ?, 'slug', 'Owned Module', 'PROVE', 'FREE', 90.00, now())", moduleId, avatarId);

        ResponseEntity<Map> response = restTemplate.exchange(
                baseUrl() + "/api/v1/avatars/" + avatarId + "/modules/" + moduleId + "/mastery-audit",
                HttpMethod.GET, new HttpEntity<>(authHeaders(attacker.token())), Map.class);

        assertThat(response.getStatusCode().value())
                .as("another user's mastery audit must not be readable")
                .isEqualTo(404);
    }
}
