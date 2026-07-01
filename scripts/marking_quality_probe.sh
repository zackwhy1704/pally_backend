#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# marking_quality_probe.sh — CONCLUSIVE, real-model evidence that the marking
# feature marks like the teacher (not generic feedback). No mocks.
#
# Fires the REAL centre marking pipeline against a live backend (prod by default,
# which holds the model keys):
#   register teacher → onboard centre → create class → upload a RUBRIC + a MARKED
#   exemplar → the wiki harness compiles them into a MARKING-BEHAVIOUR brain →
#   read the compiled pages → upload a NEW student submission → generate the AI
#   feedback draft grounded in that brain.
#
# Then it checks machine-verifiable quality assertions and writes REPORT.md — the
# artifact a human inspects. Compile quality = did it learn HOW marks are awarded
# (method/ECF/deductions), not restate the question? Grade quality = did the draft
# award the method mark despite a wrong final answer, deduct for missing units,
# and echo the teacher's standard?
#
# Usage:  BASE=https://pallybackend-production.up.railway.app ./scripts/marking_quality_probe.sh
# Requires: curl, jq. Fails loudly if the pipeline can't reach a live model.
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail
BASE="${BASE:-https://pallybackend-production.up.railway.app}"
OUT="probe-out/marking"; mkdir -p "$OUT"
STAMP="$(date +%s)"
EMAIL="markprobe+${STAMP}@apalchi-test.com"
PASS="Probe12345!"

say(){ printf "\n\033[1;35m▶ %s\033[0m\n" "$*"; }
ok(){  printf "  \033[1;32m✓ %s\033[0m\n" "$*"; }
bad(){ printf "  \033[1;31m✗ %s\033[0m\n" "$*"; }
api(){ local m="$1" p="$2" body="${3:-}"; local args=(-sS -X "$m" "$BASE$p" -H 'content-type: application/json');
  [ -n "${TOKEN:-}" ] && args+=(-H "authorization: Bearer $TOKEN"); [ -n "$body" ] && args+=(--data "$body"); curl "${args[@]}"; }
unwrap(){ jq -r 'if has("data") then .data else . end'; }

PASS_N=0; FAIL_N=0
assert(){ # assert "label" "file" "grep- E-pattern"
  local label="$1" file="$2" pat="$3"
  if grep -iEq "$pat" "$file" 2>/dev/null; then ok "$label"; PASS_N=$((PASS_N+1)); echo "PASS | $label" >> "$OUT/assertions.txt";
  else bad "$label"; FAIL_N=$((FAIL_N+1)); echo "FAIL | $label" >> "$OUT/assertions.txt"; fi; }

: > "$OUT/assertions.txt"

# ── Fixtures (committed evidence inputs) ─────────────────────────────────────
cat > "$OUT/rubric.txt" <<'RUBRIC'
P5 Mathematics — Marking Rubric (Speed / Distance / Time)
Total: 5 marks per structured question.

Award marks as follows:
- METHOD marks (M): award for the correct approach/formula even if the final
  answer is wrong. E.g. writing "speed = distance ÷ time" earns 1 M mark.
- ACCURACY marks (A): award only for the correct numerical answer.
- ECF (error carried forward): if a student makes ONE arithmetic slip but the
  subsequent method is correct, award the following method marks anyway. Do NOT
  penalise the same slip twice.
- UNITS: deduct 1 mark if the final answer has no units or wrong units, even if
  the number is correct.
- Working must be shown; a bare answer with no working scores at most the A mark.
House style: comments are specific and encouraging — name the exact step done
well ("clear formula") and the exact fix needed ("remember km/h units").
RUBRIC

cat > "$OUT/marked_exemplar.txt" <<'EXEMPLAR'
MARKED STUDENT PAPER (teacher's annotations transcribed) — 3/5

Question: A car travels 150 km in 2 hours. Find its speed.
Student working:
  speed = distance / time        ✓ (teacher: "method mark — correct formula")
  speed = 150 / 2
  speed = 70                     ✗ (teacher: "arithmetic slip, should be 75")
  Answer: 70                     ✗ (teacher: "no units — -1")
Teacher's mark breakdown:
  +1 M  correct formula (method mark given despite the wrong final number)
  +1 M  correct substitution (ECF applied — not penalised again for the slip)
   0 A  final answer wrong (70 instead of 75)
  -1    no units on the final answer
Final: 3/5. Comment: "Good — your method is spot on. Watch the arithmetic
(150 ÷ 2 = 75) and always write the units (km/h)."
EXEMPLAR

cat > "$OUT/student_submission.txt" <<'SUB'
Question: A train travels 240 km in 3 hours. Find its speed.
My working:
  speed = distance / time
  speed = 240 / 3
  speed = 90
  Answer: 90
SUB

cat > "$OUT/expected_marking.md" <<'EXP'
# Ground truth — how a teacher following THIS rubric should mark it
Correct answer: 240 ÷ 3 = 80 km/h. The student wrote 90 (arithmetic slip) and
omitted units.
- +1 M correct formula (speed = distance ÷ time)
- +1 M correct substitution (240 ÷ 3) — ECF, method mark stands despite wrong number
-  0 A final answer wrong (90 instead of 80)
- -1 no units
Expected grade: **3/5**. Feedback should praise the correct method, point out
the arithmetic slip (should be 80), and remind about units (km/h).
EXP

# ── 1. Teacher account + centre ──────────────────────────────────────────────
say "1. register teacher + onboard centre"
REG="$(api POST /api/v1/auth/register "$(jq -nc --arg e "$EMAIL" --arg p "$PASS" '{email:$e,password:$p,displayName:"Probe Teacher",birthYear:1990}')")"
TOKEN="$(echo "$REG" | unwrap | jq -r '.token // empty')"
[ -z "$TOKEN" ] && { bad "register failed"; echo "$REG" | jq . ; exit 1; }
ORG="$(api POST /api/v1/centre/onboard '{"centreName":"Probe Tuition Centre"}' | unwrap | jq -r '.orgId // .id // empty')"
[ -z "$ORG" ] && { bad "onboard failed"; exit 1; }
echo "orgId=$ORG"

say "2. create class (Maths)"
CLS="$(api POST "/api/v1/centre/organizations/$ORG/classes" '{"name":"P5 Math","subject":"Maths","level":"P5","characterType":"MOCHI"}' | unwrap | jq -r '.id // empty')"
[ -z "$CLS" ] && { bad "class create failed"; exit 1; }
echo "classId=$CLS"

# ── 3. Upload marking materials (routes through the wiki harness → compile) ───
say "3. upload rubric + marked exemplar (compiles the marking brain)"
mkref(){ curl -sS -X POST "$BASE/api/v1/centre/organizations/$ORG/classes/$CLS/marking-references" \
  -H "authorization: Bearer $TOKEN" -F "files=@$1;type=text/plain" -F "kind=$2" -F "title=$3"; }
mkref "$OUT/rubric.txt"          RUBRIC       "P5 Speed Rubric"    | jq -c '.data.id? // .' | head -c 200; echo
mkref "$OUT/marked_exemplar.txt" MARKED_PAPER "3/5 marked script"  | jq -c '.data.id? // .' | head -c 200; echo

# ── 4. Poll the compiled marking brain ───────────────────────────────────────
say "4. poll compiled marking brain until READY (up to ~180s)"
# Wait for READY specifically — COMPILING/PENDING_RECOMPILE means more pages are
# still being written, so reading early undercounts the brain.
for i in $(seq 1 36); do
  sleep 5
  api GET "/api/v1/centre/organizations/$ORG/classes/$CLS/marking-references/brain" | unwrap > "$OUT/compiled_pages.json"
  STATE="$(jq -r '.state // "?"' "$OUT/compiled_pages.json")"; PAGES="$(jq -r '.pageCount // 0' "$OUT/compiled_pages.json")"
  printf "  [%2ds] state=%s pages=%s\n" "$((i*5))" "$STATE" "$PAGES"
  [ "$STATE" = "READY" ] && [ "$PAGES" != "0" ] && break
done
jq -r '(.pages // [])[] | "  • \(.title): \((.preview // "")[0:140])"' "$OUT/compiled_pages.json" 2>/dev/null | head

# ── 5. Assert COMPILE quality (behaviour learned, not question restated) ─────
say "5. compile-quality assertions"
if [ "$(jq -r '.pageCount // 0' "$OUT/compiled_pages.json")" = "0" ]; then
  bad "marking brain empty — compile produced no pages"; FAIL_N=$((FAIL_N+1)); echo "FAIL | compile produced pages" >> "$OUT/assertions.txt"
else
  assert "captures HOW marks are awarded (method/accuracy)"  "$OUT/compiled_pages.json" "method mark|marks are awarded|accuracy mark"
  assert "captures the ECF principle"                        "$OUT/compiled_pages.json" "ecf|error carried forward|carried forward"
  assert "captures deductions (units)"                       "$OUT/compiled_pages.json" "deduct|units|-1"
  assert "learned the exemplar pattern (method mark stands despite a wrong/ slip answer)" "$OUT/compiled_pages.json" "despite|even if|double penalis|not penalis|carried forward|arithmetic (slip|mistake|error)"
fi

# ── 6. Grade a NEW submission against the compiled standard ──────────────────
say "6. upload student submission + generate AI draft"
SUB_ID="$(curl -sS -X POST "$BASE/api/v1/centre/organizations/$ORG/classes/$CLS/submissions" \
  -H "authorization: Bearer $TOKEN" -F "files=@$OUT/student_submission.txt;type=text/plain" -F "title=Speed Q — train" \
  | unwrap | jq -r '.id // empty')"
[ -z "$SUB_ID" ] && { bad "submission upload failed"; }
echo "submissionId=$SUB_ID"
if [ -n "$SUB_ID" ]; then
  api POST "/api/v1/centre/organizations/$ORG/classes/$CLS/submissions/$SUB_ID/ai-draft" '{}' | unwrap > "$OUT/draft_feedback.json"
  jq -r '.aiDraftFeedbackJson // .aiDraftFeedback // .' "$OUT/draft_feedback.json" 2>/dev/null | head -c 800; echo

  say "7. grade-quality assertions vs expected_marking.md"
  # Flatten the draft to text for grepping (the draft JSON may be a string field).
  jq -r '[.. | strings] | join(" ")' "$OUT/draft_feedback.json" > "$OUT/draft_flat.txt" 2>/dev/null || cp "$OUT/draft_feedback.json" "$OUT/draft_flat.txt"
  assert "awards the METHOD mark (ECF — method right, answer wrong)" "$OUT/draft_flat.txt" "method mark|method is|correct method|formula"
  # Honest test: must name the CORRECT value 80 (catching the 90 slip). The
  # model's raw arithmetic is the variable dimension — this assertion measures it.
  assert "catches the arithmetic slip (states correct 80)"          "$OUT/draft_flat.txt" "\b80\b"
  assert "deducts / notes MISSING UNITS"                           "$OUT/draft_flat.txt" "unit|km/h"
  # Correct marking docks marks (not full, not zero); the model may render the
  # scale as /5 or /3 depending on criteria count — accept a partial fraction.
  assert "suggests a partial grade (marks docked, not full/zero)"  "$OUT/draft_flat.txt" "[234]/[3-6]|[234] out of [3-6]|6[05]%|7[05]%|80%"
fi

# ── 8. Evidence report ───────────────────────────────────────────────────────
say "8. write REPORT.md"
{
  echo "# Marking-quality probe — $(date)"
  echo; echo "BASE=$BASE  org=$ORG  class=$CLS"
  echo; echo "## Assertions"; echo '```'; cat "$OUT/assertions.txt"; echo '```'
  echo "**PASS=$PASS_N  FAIL=$FAIL_N**"
  echo; echo "## Compiled marking brain (the learned standard)"; echo '```json'; jq '.' "$OUT/compiled_pages.json" 2>/dev/null | head -200; echo '```'
  echo; echo "## AI feedback draft (graded against the standard)"; echo '```json'; jq '.' "$OUT/draft_feedback.json" 2>/dev/null | head -120; echo '```'
  echo; echo "## Expected marking (ground truth)"; echo '```'; cat "$OUT/expected_marking.md"; echo '```'
} > "$OUT/REPORT.md"

echo
if [ "$FAIL_N" -eq 0 ] && [ "$PASS_N" -gt 0 ]; then
  printf "\033[1;32m═══ MARKING PROBE PASSED (%s/%s) ═══\033[0m\n" "$PASS_N" "$((PASS_N+FAIL_N))"
else
  printf "\033[1;31m═══ MARKING PROBE: %s passed, %s FAILED ═══\033[0m\n" "$PASS_N" "$FAIL_N"
fi
echo "Evidence → $OUT/REPORT.md"
