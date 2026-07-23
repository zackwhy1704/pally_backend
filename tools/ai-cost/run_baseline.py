#!/usr/bin/env python3
"""AI-cost baseline snapshot. Reads DATABASE_PUBLIC_URL from env (never printed) —
run via: railway run --service Postgres python3 tools/ai-cost/run_baseline.py
Writes a durable, version-controlled markdown report to tools/ai-cost/."""
import os, psycopg2

conn = psycopg2.connect(os.environ["DATABASE_PUBLIC_URL"])
cur = conn.cursor()

def q(sql, args=None):
    if args:
        cur.execute(sql, args)
    else:
        cur.execute(sql)  # no params → psycopg2 skips %-interpolation (literal % is safe)
    return cur.fetchall()

# window / provenance
(now_db, min_db, total_rows) = q("SELECT now(), MIN(created_at), COUNT(*) FROM ai_usage")[0]

# 1) THE baseline (the operator's exact query)
baseline = q("""
  SELECT COALESCE(purpose_label, call_type) AS purpose, model,
         COUNT(*) AS calls,
         ROUND(AVG(input_tokens))  AS avg_in,
         ROUND(AVG(output_tokens)) AS avg_out,
         ROUND(SUM(est_cost_micros)::numeric/NULLIF(COUNT(*),0)/1e6, 5) AS usd_per_call,
         ROUND(100.0*SUM(CASE WHEN NOT success THEN 1 ELSE 0 END)/COUNT(*),1) AS fail_pct
  FROM ai_usage
  WHERE created_at > now() - interval '30 days'
  GROUP BY 1,2 ORDER BY calls DESC""")

# 2) totals + per-model roll-up
totals = q("""
  SELECT model,
         COUNT(*) AS calls,
         ROUND(SUM(est_cost_micros)::numeric/1e6, 4) AS usd_total,
         ROUND(100.0*SUM(CASE WHEN estimated THEN 1 ELSE 0 END)/COUNT(*),1) AS est_pct
  FROM ai_usage WHERE created_at > now() - interval '30 days'
  GROUP BY 1 ORDER BY usd_total DESC NULLS LAST""")
grand = q("""SELECT COUNT(*), ROUND(SUM(est_cost_micros)::numeric/1e6,4)
             FROM ai_usage WHERE created_at > now() - interval '30 days'""")[0]

# 3) compile-cost characterization: a compile is a Gemini call. Group Gemini spend
#    per avatar → the distribution of "what one avatar's compile(s) cost". Best
#    ledger-grounded answer to "what does a compile cost" without spending money.
compile_dist = q("""
  WITH per_avatar AS (
    SELECT avatar_id,
           COUNT(*) AS calls,
           SUM(est_cost_micros)::numeric/1e6 AS usd
    FROM ai_usage
    WHERE created_at > now() - interval '30 days'
      AND model ILIKE '%gemini%' AND avatar_id IS NOT NULL
    GROUP BY avatar_id)
  SELECT COUNT(*) AS avatars,
         ROUND(AVG(usd),5)  AS avg_usd,
         ROUND(percentile_cont(0.5)  WITHIN GROUP (ORDER BY usd)::numeric,5) AS median_usd,
         ROUND(percentile_cont(0.9)  WITHIN GROUP (ORDER BY usd)::numeric,5) AS p90_usd,
         ROUND(MAX(usd),5)  AS max_usd,
         ROUND(AVG(calls),1) AS avg_calls
  FROM per_avatar""")

# 4) taxonomy: distinct call_type/purpose so labels are documented
taxonomy = q("""
  SELECT call_type, COALESCE(purpose_label,'(null)') AS purpose,
         COUNT(*) AS calls,
         COUNT(DISTINCT user_id)  AS users,
         COUNT(DISTINCT avatar_id) AS avatars
  FROM ai_usage WHERE created_at > now() - interval '30 days'
  GROUP BY 1,2 ORDER BY calls DESC""")

def table(headers, rows):
    out = ["| " + " | ".join(headers) + " |",
           "| " + " | ".join("---" for _ in headers) + " |"]
    for r in rows:
        out.append("| " + " | ".join("" if v is None else str(v) for v in r) + " |")
    return "\n".join(out)

md = []
md.append("# AI-cost baseline — pally prod `ai_usage`\n")
md.append(f"> Snapshot generated (DB clock): **{now_db:%Y-%m-%d %H:%M %Z}** · "
          f"window = last 30d · actual data spans **{min_db:%Y-%m-%d}** → now · "
          f"total rows in table = {total_rows}.\n")
md.append("> Reference values for spike detection. `usd_per_call` and `fail_pct` PER PURPOSE "
          "are the numbers the cost playbook compares against. Re-run this same script to "
          "compare; a purpose whose `usd_per_call` climbs with no code change is the signal.\n")
md.append("> ⚠️ Known ledger blind spots (do NOT read these as truth): chat prompt-cache "
          "reports cacheWrite/cacheRead=0 even when caching works (ClaudeChatProxy reads usage "
          "from message_delta/message_stop, not message_start) — verify a chat cache regression "
          "against Anthropic's console, not this ledger. Per-user is null on chat+OCR (Gemini has "
          "per-avatar). Deleted accounts NULL user_id/avatar_id retroactively → historical per-user "
          "totals drift into the unattributed bucket.\n")

md.append("## 1. Baseline — per purpose × model (THE reference table)\n")
md.append(table(["purpose","model","calls","avg_in","avg_out","usd_per_call","fail_pct"], baseline))
md.append("")
md.append("## 2. Totals & per-model roll-up\n")
md.append(f"**Grand total (30d): {grand[0]} calls, ${grand[1]}.**\n")
md.append(table(["model","calls","usd_total","est_pct (estimated, not metered)"], totals))
md.append("")
md.append("## 3. Compile cost (what one avatar's Gemini compile spend looks like)\n")
md.append("A compile is a Gemini call. Per-avatar Gemini spend distribution over the window — "
          "the ledger-grounded answer to \"what does a compile cost\" without spending to measure. "
          "For a *repeatable fixed-size* number, run one Kestrel-fixture compile via the QA harness "
          "and read that avatar's row.\n")
md.append(table(["avatars","avg_usd","median_usd","p90_usd","max_usd","avg_gemini_calls"], compile_dist))
md.append("")
md.append("## 4. Call taxonomy (documented labels)\n")
md.append(table(["call_type","purpose_label","calls","distinct_users","distinct_avatars"], taxonomy))
md.append("")

report = "\n".join(md) + "\n"
out_path = os.path.join(os.path.dirname(__file__), "baseline-2026-07-23.md")
with open(out_path, "w") as f:
    f.write(report)
print(report)
print(f"\n>>> WROTE {out_path}")
conn.close()
