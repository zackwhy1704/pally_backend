package com.pally.evidence;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * READ-ONLY forensic query over the prod ai_usage ledger — deterministic evidence
 * of "where the AI spend went", not a guess. Runs from a laptop against the Railway
 * public proxy. SELECT-only; never writes.
 *
 * <pre>
 *   export AUDIT_DB_URL='jdbc:postgresql://zephyr.proxy.rlwy.net:30670/railway'
 *   export AUDIT_DB_USER=postgres
 *   export AUDIT_DB_PASSWORD='...'
 *   ./gradlew test --tests com.pally.evidence.LedgerAuditQuery
 * </pre>
 * Skips (not fails) when the env vars are absent, so CI never touches prod.
 */
class LedgerAuditQuery {

    @Test
    void dumpLast12hSpend() throws Exception {
        String url = System.getenv("AUDIT_DB_URL");
        String user = System.getenv("AUDIT_DB_USER");
        String pass = System.getenv("AUDIT_DB_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && pass != null,
                "Set AUDIT_DB_URL/USER/PASSWORD to run the ledger audit.");

        List<String> q = List.of(
            "-- [0] does the ledger receive recent rows + is V112 metering deployed? --",
            "SELECT max(created_at) AS latest_row, "
              + "count(*) FILTER (WHERE created_at > now() - interval '24 hours') AS rows_6h, "
              + "count(*) FILTER (WHERE purpose_label IS NOT NULL AND created_at > now() - interval '24 hours') AS metered_6h "
              + "FROM ai_usage",

            "-- [1] TOTAL last 6h (ledger $, an UNDERESTIMATE at current stale rates) --",
            "SELECT count(*) AS calls, round(coalesce(sum(est_cost_micros),0)/1e6, 4) AS ledger_usd, "
              + "coalesce(sum(input_tokens),0) AS in_tok, coalesce(sum(output_tokens),0) AS out_tok "
              + "FROM ai_usage WHERE created_at > now() - interval '24 hours'",

            "-- [2] by MODEL last 6h --",
            "SELECT model, count(*) AS calls, round(sum(est_cost_micros)/1e6,4) AS ledger_usd, "
              + "sum(input_tokens) AS in_tok, sum(output_tokens) AS out_tok, "
              + "sum((estimated)::int) AS est_rows "
              + "FROM ai_usage WHERE created_at > now() - interval '24 hours' "
              + "GROUP BY model ORDER BY sum(est_cost_micros) DESC",

            "-- [3] by PURPOSE last 6h (coalesce fine label / coarse type) --",
            "SELECT coalesce(purpose_label, call_type) AS purpose, count(*) AS calls, "
              + "round(sum(est_cost_micros)/1e6,4) AS ledger_usd, sum(output_tokens) AS out_tok, "
              + "sum((NOT success)::int) AS failures "
              + "FROM ai_usage WHERE created_at > now() - interval '24 hours' "
              + "GROUP BY 1 ORDER BY sum(est_cost_micros) DESC",

            "-- [4] TIME histogram per hour, last 12h (find the burst) --",
            "SELECT date_trunc('hour', created_at) AS hour_utc, count(*) AS calls, "
              + "round(sum(est_cost_micros)/1e6,4) AS ledger_usd, sum(output_tokens) AS out_tok "
              + "FROM ai_usage WHERE created_at > now() - interval '12 hours' "
              + "GROUP BY 1 ORDER BY 1",

            "-- [5] success / estimated split last 6h --",
            "SELECT success, estimated, count(*) AS calls, round(sum(est_cost_micros)/1e6,4) AS ledger_usd "
              + "FROM ai_usage WHERE created_at > now() - interval '24 hours' GROUP BY 1,2 ORDER BY 3 DESC",

            "-- [6] TOP 20 single most expensive calls last 6h (outliers / big outputs) --",
            "SELECT created_at, coalesce(purpose_label,call_type) AS purpose, model, "
              + "input_tokens AS in_tok, output_tokens AS out_tok, "
              + "round(est_cost_micros/1e6,5) AS ledger_usd, success, estimated "
              + "FROM ai_usage WHERE created_at > now() - interval '24 hours' "
              + "ORDER BY est_cost_micros DESC LIMIT 20",

            "-- [7] RETRY/LOOP signal: same (purpose,user) with many calls last 6h --",
            "SELECT coalesce(purpose_label,call_type) AS purpose, user_id, avatar_id, count(*) AS calls "
              + "FROM ai_usage WHERE created_at > now() - interval '24 hours' "
              + "GROUP BY 1,2,3 HAVING count(*) >= 10 ORDER BY count(*) DESC LIMIT 25",

            "-- [7b] is the NEW metering deployed? purpose_label coverage + output-token outliers --",
            "SELECT count(*) AS all_rows, sum((purpose_label IS NOT NULL)::int) AS have_purpose, "
              + "sum((call_trigger IS NOT NULL)::int) AS have_trigger, "
              + "max(output_tokens) AS max_out_tok, round(avg(output_tokens)) AS avg_out_tok "
              + "FROM ai_usage",

            "-- [7c] per-model output-token shape (big avg output = thinking-inflated) --",
            "SELECT model, count(*) AS calls, round(avg(output_tokens)) AS avg_out, "
              + "max(output_tokens) AS max_out, sum(output_tokens) AS sum_out "
              + "FROM ai_usage GROUP BY model ORDER BY sum(output_tokens) DESC",

            "-- [7d] PURPOSE x MODEL crosstab (gemini-intended calls landing on haiku = the fallback leak) --",
            "SELECT coalesce(purpose_label,call_type) AS purpose, model, count(*) AS calls, "
              + "round(sum(est_cost_micros)/1e6,4) AS ledger_usd "
              + "FROM ai_usage GROUP BY 1,2 ORDER BY 1, count(*) DESC",

            "-- [R1] presence of OCR / CHAT / RELEVANCE / MARKING rows (unmetered = a gap) --",
            "SELECT coalesce(purpose_label,call_type) AS purpose, count(*) AS calls FROM ai_usage "
              + "WHERE lower(coalesce(purpose_label,call_type)) ~ 'ocr|chat|relevance|mark' GROUP BY 1",

            "-- [R2] CORRECTED cost at REAL rates (haiku 1/5, gemini 0.30/2.50 per M) --",
            "SELECT model, round(sum(CASE WHEN model LIKE 'claude-haiku%' THEN input_tokens*1.0 + output_tokens*5.0 "
              + "WHEN model = 'gemini-2.5-flash' THEN input_tokens*0.30 + output_tokens*2.50 ELSE 0 END)/1e6, 3) AS corrected_usd, "
              + "round(sum(est_cost_micros)/1e6,3) AS ledger_usd FROM ai_usage GROUP BY model",

            "-- [R3] distinct users/avatars + call_type coverage --",
            "SELECT count(DISTINCT user_id) AS users, count(DISTINCT avatar_id) AS avatars, "
              + "count(*) FILTER (WHERE user_id IS NULL) AS null_user_rows FROM ai_usage",

            "-- [8] context: last 24h + all-time totals --",
            "SELECT 'last_24h' AS window, count(*) AS calls, round(sum(est_cost_micros)/1e6,4) AS ledger_usd "
              + "FROM ai_usage WHERE created_at > now() - interval '24 hours' "
              + "UNION ALL SELECT 'all_time', count(*), round(sum(est_cost_micros)/1e6,4) FROM ai_usage"
        );

        StringBuilder out = new StringBuilder();
        try (Connection c = DriverManager.getConnection(url, user, pass);
             Statement st = c.createStatement()) {
            for (String sql : q) {
                if (sql.startsWith("--")) { out.append("\n").append(sql.replaceAll("--", "").trim()).append("\n"); continue; }
                try (ResultSet rs = st.executeQuery(sql)) {
                    ResultSetMetaData m = rs.getMetaData();
                    int cols = m.getColumnCount();
                    List<String> header = new ArrayList<>();
                    for (int i = 1; i <= cols; i++) header.add(m.getColumnLabel(i));
                    out.append(String.join(" | ", header)).append("\n");
                    while (rs.next()) {
                        List<String> row = new ArrayList<>();
                        for (int i = 1; i <= cols; i++) row.add(String.valueOf(rs.getString(i)));
                        out.append(String.join(" | ", row)).append("\n");
                    }
                }
            }
        }
        System.out.println("\n================ LEDGER AUDIT ================\n" + out
                + "\n(ledger_usd uses the CONFIGURED rates — gemini-2.5-flash output is ~8x below "
                + "the real GA price and haiku is the 3.5 rate, so REAL cost > ledger_usd.)\n");
        java.nio.file.Files.writeString(java.nio.file.Path.of("build", "evidence", "ledger-audit.txt"), out.toString());
    }
}
