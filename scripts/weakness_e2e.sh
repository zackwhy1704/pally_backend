#!/usr/bin/env bash
# ── Weakness-head CLOSED-LOOP end-to-end test (hits prod) ────────────────────
# Hypothesis: (1) LEARNS mistakes — wrong on topic A (>=2, mostly wrong), right on
# B → PROVE completion compiles a weakness brain with A not B. (2) TUTOR USES it —
# chat grounds on it ("[Weakness] grounded ..." in logs). (3) ADAPTS — answering A
# right later → A recovers (win, drops from focus). (4) PRIVATE per student.
# Requires prod env WEAKNESS_PROFILE_ENABLED=true. jq + curl.
set -uo pipefail
BASE="${BASE:-https://pallybackend-production.up.railway.app}"
OUT="${OUT:-probe-out/weakness-e2e}"; mkdir -p "$OUT"
STAMP="$(date +%s)"; EMAIL="weak-e2e+${STAMP}@apalchi-test.com"; PASS="Probe12345!"

c(){ printf "\n\033[1;36m▶ %s\033[0m\n" "$*"; }
ok(){ printf "  \033[1;32m✓ %s\033[0m\n" "$*"; }
bad(){ printf "  \033[1;31m✗ %s\033[0m\n" "$*"; }
api(){ local m="$1" p="$2" body="${3:-}"; local a=(-sS -X "$m" "$BASE$p" -H 'content-type: application/json'); [ -n "${TOKEN:-}" ] && a+=(-H "authorization: Bearer $TOKEN"); [ -n "$body" ] && a+=(--data "$body"); curl "${a[@]}"; }
uw(){ jq 'if type=="object" and has("data") then .data else . end'; }

# ── 0. student + avatar ──────────────────────────────────────────────────────
c "0. register student + create MATHS avatar"
TOKEN="$(api POST /api/v1/auth/register "$(jq -nc --arg e "$EMAIL" --arg p "$PASS" '{email:$e,password:$p,displayName:"E2E Student",birthYear:2005}')" | uw | jq -r '.token // empty')"
[ -z "$TOKEN" ] && { bad "register failed"; exit 1; }
AV="$(api POST /api/v1/avatars '{"name":"Maths Mochi","subject":"MATHS","characterType":"MOCHI","gradeLevel":"P5","curriculumType":"SG-MOE"}' | uw | jq -r '.id // empty')"
[ -z "$AV" ] && { bad "avatar create failed"; exit 1; }
echo "  avatarId=$AV  email=$EMAIL"

# ── 1. upload RICH notes (enough content per topic for PROVE gen) ─────────────
c "1. upload rich notes → compile wiki"
cat > "$OUT/notes.txt" <<'NOTES'
Primary 5 Mathematics — Complete Study Notes

## Dividing Fractions
Dividing fractions uses the "keep, change, flip" rule (also called multiply by the
reciprocal). Step 1: KEEP the first fraction exactly as it is. Step 2: CHANGE the
division sign to a multiplication sign. Step 3: FLIP the second fraction (swap its
numerator and denominator to make the reciprocal). Then multiply the numerators
together and the denominators together, and simplify.
Worked example: 3/4 divided by 2/5. Keep 3/4, change to multiply, flip 2/5 to 5/2.
So 3/4 x 5/2 = 15/8 = 1 and 7/8. Common mistake: students flip the FIRST fraction
instead of the second, or forget to change the sign. Another mistake is multiplying
straight across without flipping. Always flip only the divisor (the second fraction).
A fraction divided by a whole number: 3/4 divided by 2 = 3/4 x 1/2 = 3/8.

## Simplifying Ratios
A ratio compares two or more quantities, written as a:b. To simplify a ratio,
divide every part by their highest common factor (HCF). Example: 12:18. The HCF of
12 and 18 is 6, so 12:18 simplifies to 2:3. To find a missing value in equivalent
ratios, use cross-multiplication or the scale factor. Example: 2:3 = 8:? — the
scale factor is 4 (because 2 x 4 = 8), so the missing value is 3 x 4 = 12, giving
8:12. Ratios must be in the same units before simplifying. Common mistake: only
dividing one side, or forgetting to convert units first (e.g. cm and m).

## Percentages
Percent means "out of 100". To convert a fraction to a percentage, multiply by 100
and add the percent sign: 3/4 = 3/4 x 100 = 75%. To find a percentage of a number,
change the percentage to a decimal (divide by 100) and multiply. Example: 20% of 60
= 0.2 x 60 = 12. To find what percentage one number is of another, divide and
multiply by 100: 15 out of 50 = 15/50 x 100 = 30%. Percentage increase: add the
extra percent; percentage decrease: subtract. Common mistake: forgetting to convert
the percent to a decimal before multiplying, or dividing by the wrong number.
NOTES
curl -sS -X POST "$BASE/api/v1/avatars/$AV/files?skipRelevance=true" -H "authorization: Bearer $TOKEN" \
  -F "file=@$OUT/notes.txt;type=text/plain" | uw | jq -c '{fileId,extractedChars}' || true
api POST "/api/v1/avatars/$AV/wiki/recompile" >/dev/null || true

c "2. poll wiki until pages compiled (up to ~4 min)"
SLUGS=""
for i in $(seq 1 48); do
  sleep 5
  PAGES="$(api GET "/api/v1/avatars/$AV/wiki/pages" | uw)"
  N="$(echo "$PAGES" | jq -r 'if type=="array" then length elif has("pages") then (.pages|length) elif has("data") then (.data|length) else 0 end' 2>/dev/null || echo 0)"
  if [ "${N:-0}" -ge 2 ]; then
    SLUGS="$(echo "$PAGES" | jq -r '(.pages // .data // .)[].slug' 2>/dev/null | tr '\n' ' ')"
    ok "wiki ready: $N pages → slugs: $SLUGS"; break
  fi
  echo "  ...waiting (pages=$N, ${i}0s)"
done
[ -z "$SLUGS" ] && { bad "wiki never compiled"; exit 1; }
WEAK="$(echo "$SLUGS" | tr ' ' '\n' | grep -iE 'frac|divid' | head -1)"; [ -z "$WEAK" ] && WEAK="$(echo "$SLUGS" | awk '{print $1}')"
STRONG="$(echo "$SLUGS" | tr ' ' '\n' | grep -viE "^$WEAK$" | head -1)"
echo "  WEAK target = $WEAK   |   STRONG target = $STRONG"

# ── 3. daily quiz — WRONG on WEAK, RIGHT on others (case-insensitive match) ───
c "3. daily quiz: intentionally wrong on '$WEAK', right on others (x2)"
submit_quiz(){
  local Q; Q="$(api GET "/api/v1/avatars/$AV/quiz/daily" | uw)"; echo "$Q" > "$OUT/quiz.json"
  [ "$(echo "$Q" | jq 'length')" -eq 0 ] && return 1
  local payload
  payload="$(echo "$Q" | jq -c --arg weak "$WEAK" '
    reduce .[] as $x ({answers:{},correctMap:{},topicMap:{},confidenceMap:{}};
      .correctMap[$x.id]=$x.correctIndex | .topicMap[$x.id]=$x.sourcePageSlug
      | if ($x.sourcePageSlug|ascii_downcase)==($weak|ascii_downcase)
        then .answers[$x.id]=(($x.correctIndex+1) % ($x.options|length)) | .confidenceMap[$x.id]="LOW"
        else .answers[$x.id]=$x.correctIndex | .confidenceMap[$x.id]="HIGH" end)
    | .durationSeconds=90')"
  api POST "/api/v1/avatars/$AV/quiz/answers" "$payload" | uw | jq -c '{score,total}'
}
submit_quiz || true; submit_quiz || true

c "4. verify raw signal (topic-mastery); top up '$WEAK' wrong if needed"
TM="$(api GET "/api/v1/avatars/$AV/topic-mastery" | uw)"; echo "$TM" | jq -c '.'
# Guarantee WEAK is weak (>=2 attempts, ratio<0.6) using the same real slug casing.
RSLUG="$(echo "$TM" | jq -r --arg w "$WEAK" '(.[]?|select((.topicSlug|ascii_downcase)==($w|ascii_downcase))|.topicSlug)' | head -1)"; [ -z "$RSLUG" ] && RSLUG="$WEAK"
WR="$(echo "$TM" | jq -r --arg w "$RSLUG" '(.[]?|select(.topicSlug==$w)|.mastery)//1' | head -1)"
WA="$(echo "$TM" | jq -r --arg w "$RSLUG" '(.[]?|select(.topicSlug==$w)|.attempts)//0' | head -1)"
if awk "BEGIN{exit !($WA+0 < 2 || $WR+0 >= 0.6)}"; then
  echo "  topping up '$RSLUG' wrong"
  api POST "/api/v1/avatars/$AV/quiz/answers" "$(jq -nc --arg w "$RSLUG" '{answers:{wq1:0,wq2:0,wq3:0},correctMap:{wq1:1,wq2:1,wq3:1},topicMap:{wq1:$w,wq2:$w,wq3:$w},confidenceMap:{wq1:"LOW",wq2:"LOW",wq3:"LOW"},durationSeconds:60}')" >/dev/null
fi
echo "  WEAK slug used = $RSLUG → $(api GET "/api/v1/avatars/$AV/topic-mastery" | uw | jq -c --arg w "$RSLUG" '(.[]?|select(.topicSlug==$w))')"

# ── 5. complete ANY module (retry PROVE start until items generate) ──────────
c "5. drive a module to COMPLETE to fire the trigger"
api POST "/api/v1/avatars/$AV/modules/generate" >/dev/null || true
MIDS="$(api GET "/api/v1/avatars/$AV/modules" | uw | jq -r '(.[]? // (.modules[]?))|.id')"
complete_module(){ # $1=moduleId → echoes COMPLETE on success
  local mid="$1" r stage subs next tries
  for r in 1 2 3 4 5; do
    ST="$(api POST "/api/v1/avatars/$AV/modules/$mid/start" | uw)"
    stage="$(echo "$ST" | jq -r '.stage // empty')"
    # NOTE: TEST uses a LOW score on purpose — PROVE-gen prioritises poorly-scored
    # concepts, and all-perfect TEST scores make Gemini return 0 prove questions.
    subs="$(echo "$ST" | jq -c '[ (.items // [])[] | {itemId:.id, response:( if (.stage//"")=="TEST" or (.type//"")=="TEST" then "{\"score\":0.2,\"concept\":\"dividing fractions\"}" elif (.stage//"")=="PROVE" or (.type//"")=="PROVE" then "Keep the first fraction, change divide to multiply, and flip the second fraction, then multiply across." else "done" end )} ]')"
    if [ "$(echo "$subs" | jq 'length')" -eq 0 ]; then
      # PROVE items may generate slowly / return 0 on thin pages — retry start.
      echo "  [$mid] stage=$stage no items (retry $r)"; sleep 6; continue
    fi
    next="$(api POST "/api/v1/avatars/$AV/modules/$mid/submit" "$(jq -nc --argjson s "$subs" '{submissions:$s,durationSeconds:45}')" | uw | jq -r '.nextStage // empty')"
    echo "  [$mid] stage=$stage → $next"
    [ "$next" = "COMPLETE" ] && { echo "COMPLETE"; return 0; }
  done
  return 1
}
FIRED=0
for mid in $MIDS; do
  echo "  trying module $mid"
  if [ "$(complete_module "$mid")" = "COMPLETE" ]; then ok "module COMPLETE — trigger fired"; FIRED=1; break; fi
done
[ "$FIRED" = "0" ] && bad "no module could complete (PROVE gen kept returning 0 items)"

# ── 6. assert the loop LEARNED the mistake ───────────────────────────────────
c "6. poll /weakness/focus — expect focus areas (incl. '$RSLUG')"
for i in $(seq 1 30); do
  sleep 5
  F="$(api GET "/api/v1/weakness/focus?subject=MATHS" | uw)"; echo "$F" > "$OUT/focus_after_mistake.json"
  [ "$(echo "$F" | jq -r '.enabled')" != "true" ] && { bad "pilot flag OFF"; break; }
  if [ "$(echo "$F" | jq -r '.focusAreas|length')" -ge 1 ]; then
    ok "LOOP LEARNED — focusAreas: $(echo "$F" | jq -c '[.focusAreas[].title]')"; break
  fi
  echo "  ...waiting for weakness compile (${i}x5s)"
done

# ── 7. tutor USES it (grounding) ─────────────────────────────────────────────
c "7. fire a chat (grounding logs server-side as [Weakness] grounded ... avatarId=$AV)"
curl -sS -N -X POST "$BASE/api/v1/avatars/$AV/chat" -H "authorization: Bearer $TOKEN" \
  -H 'content-type: application/json' --data '{"message":"help me practise maths"}' --max-time 30 >/dev/null 2>&1 || true
ok "chat fired"

# ── 8. learn the IMPROVEMENT (recovery) ──────────────────────────────────────
c "8. answer '$RSLUG' correctly, complete another module → expect recovery"
api POST "/api/v1/avatars/$AV/quiz/answers" "$(jq -nc --arg w "$RSLUG" '{answers:{rq1:1,rq2:1,rq3:1,rq4:1,rq5:1,rq6:1,rq7:1,rq8:1},correctMap:{rq1:1,rq2:1,rq3:1,rq4:1,rq5:1,rq6:1,rq7:1,rq8:1},topicMap:{rq1:$w,rq2:$w,rq3:$w,rq4:$w,rq5:$w,rq6:$w,rq7:$w,rq8:$w},confidenceMap:{rq1:"HIGH",rq2:"HIGH",rq3:"HIGH",rq4:"HIGH",rq5:"HIGH",rq6:"HIGH",rq7:"HIGH",rq8:"HIGH"},durationSeconds:60}')" >/dev/null
echo "  post-recovery: $(api GET "/api/v1/avatars/$AV/topic-mastery" | uw | jq -c --arg w "$RSLUG" '(.[]?|select(.topicSlug==$w))')"
for mid in $MIDS; do
  [ "$(complete_module "$mid")" = "COMPLETE" ] && { ok "recovery module COMPLETE"; break; }
done
c "9. poll /weakness/focus — expect '$RSLUG' recovered (in recentWins / gone from focus)"
for i in $(seq 1 30); do
  sleep 5
  F="$(api GET "/api/v1/weakness/focus?subject=MATHS" | uw)"; echo "$F" > "$OUT/focus_after_recovery.json"
  echo "  focus=$(echo "$F" | jq -c '[.focusAreas[].title]')  wins=$(echo "$F" | jq -c '.recentWins')"
  echo "$F" | jq -e --arg w "$RSLUG" '.recentWins | map(ascii_downcase) | any(contains($w|ascii_downcase))' >/dev/null 2>&1 && { ok "LOOP ADAPTED — '$RSLUG' recovered"; break; }
done

c "DONE — avatarId=$AV (grep railway logs for [Weakness])"
