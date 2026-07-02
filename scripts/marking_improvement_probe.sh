#!/usr/bin/env bash
# ── Marking-assistant IMPROVEMENT probe (hard data, causal before→after) ──────
# The marking assistant is already strong on GENERIC marking (ECF, units, correct
# answer) from the model's priors — so those can't show "learning" (no headroom).
# This probe proves learning on a CENTRE-SPECIFIC convention the model canNOT guess:
#   • this centre marks OUT OF 8 (not the default /5), and
#   • a missing unit costs 2 marks (not the usual 1).
# Round 1 marks with a minimal rubric (no scale) → the AI defaults (≈/5, −1 units).
# Then we TRAIN on the teacher's marked exemplars (which use /8 and −2) and recompile.
# Round 2 must now adopt the centre's /8 scale — a behaviour it could ONLY have learned.
# Agreement measured from the real AI draft vs ground truth — never a rating.
set -uo pipefail
BASE="${BASE:-https://pallybackend-production.up.railway.app}"
OUT="${OUT:-probe-out/marking-improvement}"; mkdir -p "$OUT"
STAMP="$(date +%s)"; EMAIL="markimp+${STAMP}@apalchi-test.com"; PASS="Probe12345!"
api(){ local m="$1" p="$2" b="${3:-}"; local a=(-sS -X "$m" "$BASE$p" -H 'content-type: application/json'); [ -n "${TOKEN:-}" ] && a+=(-H "authorization: Bearer $TOKEN"); [ -n "$b" ] && a+=(--data "$b"); curl "${a[@]}"; }
uw(){ jq 'if type=="object" and has("data") then .data else . end'; }

cat > "$OUT/rubric_minimal.txt" <<'R'
P5 Maths marking — general guidance.
Mark each structured question. Give credit for correct working and the correct
final answer. Be encouraging and specific.
R
cat > "$OUT/exemplar_1.txt" <<'E'
MARKED PAPER (teacher annotations) — 6 / 8
Q: A car travels 150 km in 2 hours. Find its speed.
  speed = distance / time     [tick] +2 METHOD (this centre gives 2 for method)
  = 150 / 2 = 75              [tick] +4 ACCURACY (this centre weights accuracy 4/8)
  Answer: 75                  [cross] NO UNITS — this centre deducts 2 for missing units
Breakdown out of 8: +2 method, +4 accuracy, -2 units missing = 6/8.
"Great work — but in our centre a missing unit is -2, so always write km/h."
E
cat > "$OUT/exemplar_2.txt" <<'E'
MARKED PAPER (teacher annotations) — 4 / 8
Q: A tank fills 240 L in 4 min. Find the rate.
  rate = volume / time        [tick] +2 METHOD
  = 240/4 = 60               [tick] +4 ACCURACY
  Answer: 60                  [cross] units missing (L/min) — this centre deducts 2
Total out of 8: +2 +4 -2 = 4/8. "Correct, but -2 for the missing units (our centre rule)."
E
cat > "$OUT/submission.txt" <<'S'
Q: A train travels 240 km in 3 hours. Find its speed.
  speed = distance / time
  speed = 240 / 3
  speed = 80
  Answer: 80
S
cat > "$OUT/expected.md" <<'G'
# Ground truth (teacher applying THIS CENTRE's standard)
Correct answer 240/3 = 80 km/h (student got it right) but NO units.
This centre marks OUT OF 8 and deducts 2 for missing units.
Expected: +2 method, +4 accuracy, -2 units = 6/8. The draft should use the /8 scale
and apply the -2 units rule — neither is guessable without the exemplars.
G

echo "== marking-improvement probe @ $BASE =="
TOKEN="$(api POST /api/v1/auth/register "$(jq -nc --arg e "$EMAIL" --arg p "$PASS" '{email:$e,password:$p,displayName:"Probe Teacher",birthYear:1990}')" | uw | jq -r '.token // empty')"
[ -z "$TOKEN" ] && { echo "FAIL: register"; exit 1; }
ORG="$(api POST /api/v1/centre/onboard '{"centreName":"MarkImp Centre"}' | uw | jq -r '.orgId // .id // empty')"
CLS="$(api POST "/api/v1/centre/organizations/$ORG/classes" '{"name":"P5 Math","subject":"Maths","level":"P5","characterType":"MOCHI"}' | uw | jq -r '.id // empty')"
[ -z "$CLS" ] && { echo "FAIL: class"; exit 1; }
echo "orgId=$ORG classId=$CLS"

mkref(){ curl -sS -X POST "$BASE/api/v1/centre/organizations/$ORG/classes/$CLS/marking-references" -H "authorization: Bearer $TOKEN" -F "files=@$1;type=text/plain" -F "kind=$2" -F "title=$3" >/dev/null; }
poll_ready(){ for i in $(seq 1 36); do sleep 5; local b st pg; b="$(api GET "/api/v1/centre/organizations/$ORG/classes/$CLS/marking-references/brain" | uw)"; st="$(echo "$b" | jq -r '.state//"?"')"; pg="$(echo "$b" | jq -r '.pageCount//0')"; [ "$st" = "READY" ] && [ "${pg:-0}" -ge 1 ] && { echo "  brain READY ($pg pages)"; return 0; }; done; echo "  (brain not READY in time)"; return 1; }

draft_and_score(){ # $1=label → echoes "scale8|units2|method|ans80" + writes draft
  local label="$1" sid t
  sid="$(curl -sS -X POST "$BASE/api/v1/centre/organizations/$ORG/classes/$CLS/submissions" -H "authorization: Bearer $TOKEN" -F "files=@$OUT/submission.txt;type=text/plain" -F "title=Speed $label" | uw | jq -r '.id // empty')"
  [ -z "$sid" ] && { echo "0|0|0|0"; return; }
  api POST "/api/v1/centre/organizations/$ORG/classes/$CLS/submissions/$sid/ai-draft" '{}' | uw > "$OUT/draft_${label}.json"
  jq -r '[.. | strings] | join(" ")' "$OUT/draft_${label}.json" 2>/dev/null > "$OUT/draft_${label}.txt" || cp "$OUT/draft_${label}.json" "$OUT/draft_${label}.txt"
  t="$(tr 'A-Z' 'a-z' < "$OUT/draft_${label}.txt")"
  local scale8 units2 method ans80
  echo "$t" | grep -qE "/ ?8\b|out of 8|of 8\b"                          && scale8=1 || scale8=0
  echo "$t" | grep -qE "\-?2 ?(marks?|mark)|deduct.{0,12}2|two marks?"    && units2=1 || units2=0
  echo "$t" | grep -qE "method|formula|correct approach"                 && method=1 || method=0
  echo "$t" | grep -qE "\b80\b"                                          && ans80=1  || ans80=0
  echo "$scale8|$units2|$method|$ans80"
}

echo "-- Round 1: minimal rubric only (no scale) --"
mkref "$OUT/rubric_minimal.txt" GUIDELINE "Minimal rubric"
poll_ready || true
R1="$(draft_and_score round1)"; echo "  round1 [scale8|units2|method|ans80] = $R1"

echo "-- Train: upload the centre's marked exemplars (/8, -2 units) + recompile --"
mkref "$OUT/exemplar_1.txt" MARKED_PAPER "Centre exemplar 6/8"
mkref "$OUT/exemplar_2.txt" MARKED_PAPER "Centre exemplar 4/8"
poll_ready || true

echo "-- Round 2: re-mark the same paper with the trained standard --"
R2="$(draft_and_score round2)"; echo "  round2 [scale8|units2|method|ans80] = $R2"

IFS='|' read -r c1 u1 m1 a1 <<< "$R1"
IFS='|' read -r c2 u2 m2 a2 <<< "$R2"
learned1=$((c1+u1)); learned2=$((c2+u2))
{
  echo "# Marking-assistant improvement — hard-data before→after"
  echo
  echo "Same paper marked twice. Round 1 = minimal rubric (no scale). Round 2 = after"
  echo "training on the centre's marked exemplars. The CENTRE-SPECIFIC rules (unguessable"
  echo "without the exemplars): marks are **out of 8**, and a missing unit is **-2**."
  echo "Generic concepts (method, correct answer) are shown for context — the model"
  echo "already knows those, so they can't demonstrate learning."
  echo
  echo "| signal | Round 1 | Round 2 | type |"
  echo "|---|---|---|---|"
  echo "| uses the centre's /8 scale | $c1 | $c2 | **centre-specific (learned)** |"
  echo "| applies -2 units rule      | $u1 | $u2 | **centre-specific (learned)** |"
  echo "| method mark                | $m1 | $m2 | generic (prior) |"
  echo "| finds correct answer 80    | $a1 | $a2 | generic (prior) |"
  echo "| **centre-specific total /2** | **$learned1** | **$learned2** |"
  echo
  if [ "$learned2" -gt "$learned1" ]; then
    echo "**RESULT: LEARNED.** The assistant adopted centre conventions it could not guess"
    echo "($learned1→$learned2 /2) only after training on the exemplars. The correction loop works."
    VERDICT=PASS
  else
    echo "**RESULT: NO measurable learning** ($learned1→$learned2 /2) on the centre-specific rules."
    echo "Either the exemplars didn't recompile in time, or the compile doesn't capture the"
    echo "centre convention — investigate before trusting the loop."
    VERDICT=FAIL
  fi
  echo
  echo "## Round 1 AI draft (minimal rubric)"; echo '```'; sed -e 's/\\n/\n/g' "$OUT/draft_round1.txt" | head -c 1600; echo; echo '```'
  echo "## Round 2 AI draft (after training)"; echo '```'; sed -e 's/\\n/\n/g' "$OUT/draft_round2.txt" | head -c 1600; echo; echo '```'
  echo
  echo "## Caveats"
  echo "- Live PRIMARY (Gemini) compiler path; the Claude fallback is unit-tested"
  echo "  (MarkingCompilerPromptTest) and both heads share WikiCompilerPrompts.markingHeader,"
  echo "  so they can't drift. Forcing the fallback tier isn't reachable via the public API."
  echo "- GroundednessVerifier is logging ~40% flags vs its 20% ceiling. That gate scores"
  echo "  MODULE content, not these marking drafts, so it does NOT skew this number — but it's"
  echo "  a real calibration signal (surface, don't blind-tune: it may be real, not noise)."
} > "$OUT/REPORT.md"

echo; echo "======== REPORT ($OUT/REPORT.md) ========"; sed -n '1,34p' "$OUT/REPORT.md"
echo; echo "VERDICT: ${VERDICT:-FAIL}"
[ "${VERDICT:-FAIL}" = "PASS" ] || exit 1
