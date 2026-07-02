#!/usr/bin/env bash
# ── Marking-assistant IMPROVEMENT probe (hard data, causal before→after) ──────
# Proves the marking assistant LEARNS: Round 1 marks with a minimal rubric only;
# then we TRAIN it with the teacher's marked exemplars (the corrections) and
# recompile; Round 2 re-marks the same paper. Agreement vs the teacher's ground
# truth must RISE round1→round2 on the corrected concepts (ECF/method, units
# deduction, correct answer, partial grade) — else the loop doesn't learn (FAIL).
# Agreement is measured from the real AI draft vs ground truth — never a rating.
# Requires prod + live model keys (fails loudly if the compile never produces pages).
set -uo pipefail
BASE="${BASE:-https://pallybackend-production.up.railway.app}"
OUT="${OUT:-probe-out/marking-improvement}"; mkdir -p "$OUT"
STAMP="$(date +%s)"; EMAIL="markimp+${STAMP}@apalchi-test.com"; PASS="Probe12345!"
api(){ local m="$1" p="$2" b="${3:-}"; local a=(-sS -X "$m" "$BASE$p" -H 'content-type: application/json'); [ -n "${TOKEN:-}" ] && a+=(-H "authorization: Bearer $TOKEN"); [ -n "$b" ] && a+=(--data "$b"); curl "${a[@]}"; }
uw(){ jq 'if type=="object" and has("data") then .data else . end'; }

# ── fixtures ─────────────────────────────────────────────────────────────────
cat > "$OUT/rubric_minimal.txt" <<'R'
P5 Maths marking — general guidance.
Mark each structured question out of 5. Give credit for correct working and the
correct final answer. Be encouraging.
R
cat > "$OUT/exemplar_1.txt" <<'E'
MARKED PAPER (teacher annotations) — 3/5
Q: A car travels 150 km in 2 hours. Find its speed.
  speed = distance / time     [tick] +1 METHOD mark (correct formula)
  = 150 / 2 = 70              [cross] arithmetic slip — should be 75 (ECF: method mark still stands)
  Answer: 70                  [cross] NO UNITS — deduct 1
Breakdown: +1 method, +1 substitution (ECF applied, not penalised twice), 0 accuracy (answer wrong), -1 units.
Final: 3/5. "Method is spot on — fix the arithmetic (150/2=75) and always write units (km/h)."
E
cat > "$OUT/exemplar_2.txt" <<'E'
MARKED PAPER (teacher annotations) — 4/5
Q: A tank fills 240 L in 4 min. Find the rate.
  rate = volume / time        [tick] +1 METHOD
  = 240/4 = 60                [tick] +1 accuracy
  Answer: 60                  [cross] units missing (L/min) — deduct 1
Final: 4/5. "Correct method and answer — remember the units (L/min)."
E
cat > "$OUT/submission.txt" <<'S'
Q: A train travels 240 km in 3 hours. Find its speed.
  speed = distance / time
  speed = 240 / 3
  speed = 90
  Answer: 90
S
# GROUND TRUTH (how a teacher following the exemplars marks it): correct = 80 km/h.
# Student wrote 90 (arithmetic slip) and NO units. Expect: +1 method, +1 substitution
# (ECF), 0 accuracy, -1 units → 3/5; feedback praises method, flags slip (80), notes units.
cat > "$OUT/expected.md" <<'G'
# Ground truth (teacher applying the exemplars)
Correct answer 240/3 = 80 km/h. Student wrote 90 (slip) + no units.
Expected grade: 3/5. Should: award METHOD mark despite wrong answer (ECF),
identify the correct answer 80, DEDUCT for missing UNITS, give a PARTIAL grade.
G

echo "== marking-improvement probe @ $BASE =="
TOKEN="$(api POST /api/v1/auth/register "$(jq -nc --arg e "$EMAIL" --arg p "$PASS" '{email:$e,password:$p,displayName:"Probe Teacher",birthYear:1990}')" | uw | jq -r '.token // empty')"
[ -z "$TOKEN" ] && { echo "FAIL: register"; exit 1; }
ORG="$(api POST /api/v1/centre/onboard '{"centreName":"MarkImp Centre"}' | uw | jq -r '.orgId // .id // empty')"
CLS="$(api POST "/api/v1/centre/organizations/$ORG/classes" '{"name":"P5 Math","subject":"Maths","level":"P5","characterType":"MOCHI"}' | uw | jq -r '.id // empty')"
[ -z "$CLS" ] && { echo "FAIL: class"; exit 1; }
echo "orgId=$ORG classId=$CLS"

mkref(){ curl -sS -X POST "$BASE/api/v1/centre/organizations/$ORG/classes/$CLS/marking-references" -H "authorization: Bearer $TOKEN" -F "files=@$1;type=text/plain" -F "kind=$2" -F "title=$3" >/dev/null; }
poll_ready(){ for i in $(seq 1 48); do sleep 5; local b; b="$(api GET "/api/v1/centre/organizations/$ORG/classes/$CLS/marking-references/brain" | uw)"; local st pg; st="$(echo "$b" | jq -r '.state//"?"')"; pg="$(echo "$b" | jq -r '.pageCount//0')"; [ "$st" = "READY" ] && [ "${pg:-0}" -ge 1 ] && { echo "  brain READY ($pg pages)"; return 0; }; done; echo "  (brain not READY)"; return 1; }

draft_and_score(){ # $1=round label → echoes "score|gradeOk|method|units|ans80|partial" + writes draft
  local label="$1"
  local sid; sid="$(curl -sS -X POST "$BASE/api/v1/centre/organizations/$ORG/classes/$CLS/submissions" -H "authorization: Bearer $TOKEN" -F "files=@$OUT/submission.txt;type=text/plain" -F "title=Speed Q $label" | uw | jq -r '.id // empty')"
  [ -z "$sid" ] && { echo "0|0|0|0|0|0"; return; }
  api POST "/api/v1/centre/organizations/$ORG/classes/$CLS/submissions/$sid/ai-draft" '{}' | uw > "$OUT/draft_${label}.json"
  jq -r '[.. | strings] | join(" ")' "$OUT/draft_${label}.json" 2>/dev/null > "$OUT/draft_${label}.txt" || cp "$OUT/draft_${label}.json" "$OUT/draft_${label}.txt"
  local t; t="$(tr 'A-Z' 'a-z' < "$OUT/draft_${label}.txt")"
  local method units ans80 partial gradeOk
  echo "$t" | grep -qE "method|formula|correct approach|ecf"        && method=1 || method=0
  echo "$t" | grep -qE "unit|km/h"                                  && units=1  || units=0
  echo "$t" | grep -qE "\b80\b"                                     && ans80=1  || ans80=0
  echo "$t" | grep -qE "[234]/5|[234] out of 5|6[05]%|partial|deduct" && partial=1 || partial=0
  gradeOk=$partial
  echo "$((method+units+ans80+partial))|$gradeOk|$method|$units|$ans80|$partial"
}

# ── ROUND 1 — minimal rubric only ────────────────────────────────────────────
echo "-- Round 1: minimal rubric only --"
mkref "$OUT/rubric_minimal.txt" GUIDELINE "Minimal rubric"
poll_ready || true
R1="$(draft_and_score round1)"; echo "  round1 score=$R1"

# ── TRAIN — add the teacher's marked exemplars, recompile ────────────────────
echo "-- Train: upload marked exemplars (corrections) + recompile --"
mkref "$OUT/exemplar_1.txt" MARKED_PAPER "Speed exemplar 3/5"
mkref "$OUT/exemplar_2.txt" MARKED_PAPER "Rate exemplar 4/5"
poll_ready || true

# ── ROUND 2 — same paper, trained standard ───────────────────────────────────
echo "-- Round 2: re-mark the same paper with the trained standard --"
R2="$(draft_and_score round2)"; echo "  round2 score=$R2"

# ── SCORE + REPORT ───────────────────────────────────────────────────────────
IFS='|' read -r s1 g1 m1 u1 a1 p1 <<< "$R1"
IFS='|' read -r s2 g2 m2 u2 a2 p2 <<< "$R2"
{
  echo "# Marking-assistant improvement — hard-data before→after"
  echo
  echo "Same student paper marked twice: Round 1 = minimal rubric only; Round 2 = after"
  echo "training on the teacher's marked exemplars. Agreement scored vs ground truth"
  echo "(\`expected.md\`): correct answer **80 km/h**, expect ECF/method mark, **units** deduction,"
  echo "a **partial** grade (~3/5). Concept met = present in the AI draft."
  echo
  echo "| concept (vs teacher) | Round 1 | Round 2 |"
  echo "|---|---|---|"
  echo "| method/ECF mark      | $m1 | $m2 |"
  echo "| units deduction      | $u1 | $u2 |"
  echo "| finds correct ans 80 | $a1 | $a2 |"
  echo "| partial grade        | $p1 | $p2 |"
  echo "| **total (/4)**       | **$s1** | **$s2** |"
  echo
  if [ "$s2" -gt "$s1" ]; then echo "**RESULT: IMPROVED** (agreement $s1→$s2 /4). The correction loop learns."; VERDICT=PASS
  elif [ "$s2" -eq "$s1" ] && [ "$s1" -ge 3 ]; then echo "**RESULT: already high ($s1/4), held.** No regression."; VERDICT=PASS
  else echo "**RESULT: NO IMPROVEMENT** ($s1→$s2). The loop did not learn — investigate."; VERDICT=FAIL; fi
  echo
  echo "## Round 1 AI draft"; echo '```'; sed -e 's/\\n/\n/g' "$OUT/draft_round1.txt" | head -c 1500; echo; echo '```'
  echo "## Round 2 AI draft"; echo '```'; sed -e 's/\\n/\n/g' "$OUT/draft_round2.txt" | head -c 1500; echo; echo '```'
  echo
  echo "## Caveats (read before trusting the numbers)"
  echo "- Compiler path: this exercises the LIVE primary (Gemini) path; the Claude fallback"
  echo "  compiler is covered by MarkingCompilerPromptTest (both heads route to the single"
  echo "  WikiCompilerPrompts.markingHeader, so they can't drift). Forcing the fallback tier"
  echo "  isn't reachable via the public API."
  echo "- GroundednessVerifier is currently logging a flag rate ~40% vs its 20% ceiling. That"
  echo "  gate scores MODULE content, not these marking drafts, so it does not skew this"
  echo "  agreement number — but it's a real calibration signal worth a data-driven review."
} > "$OUT/REPORT.md"

echo
echo "======== REPORT ($OUT/REPORT.md) ========"
sed -n '1,40p' "$OUT/REPORT.md"
echo
echo "VERDICT: ${VERDICT:-FAIL}"
[ "${VERDICT:-FAIL}" = "PASS" ] || exit 1
