-- AI-cost baseline. Reference values for spike detection (usd_per_call + fail_pct
-- per purpose are the numbers the cost playbook compares against).
-- Run: railway run --service Postgres python3 tools/ai-cost/run_baseline.py
SELECT COALESCE(purpose_label, call_type) AS purpose, model,
       COUNT(*) AS calls,
       ROUND(AVG(input_tokens))  AS avg_in,
       ROUND(AVG(output_tokens)) AS avg_out,
       ROUND(SUM(est_cost_micros)::numeric/NULLIF(COUNT(*),0)/1e6, 5) AS usd_per_call,
       ROUND(100.0*SUM(CASE WHEN NOT success THEN 1 ELSE 0 END)/COUNT(*),1) AS fail_pct
FROM ai_usage
WHERE created_at > now() - interval '30 days'
GROUP BY 1,2 ORDER BY calls DESC;
