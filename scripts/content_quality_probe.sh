#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# content_quality_probe.sh — fire the REAL content-generation pipeline end to end
# with a mock study document, then dump everything that was generated so we can
# judge quality: compiled wiki pages, modules, daily quiz, flashcards.
#
# It registers a throwaway account, creates a Mochi (avatar), uploads notes,
# compiles the wiki, generates modules, and pulls quiz/flashcards. Each step's
# raw JSON is saved under ./probe-out/ for inspection.
#
# Usage:  BASE=https://pallybackend-production.up.railway.app ./scripts/content_quality_probe.sh
#         (defaults to prod). Requires: curl, jq.
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail
BASE="${BASE:-https://pallybackend-production.up.railway.app}"
OUT="probe-out"; mkdir -p "$OUT"
STAMP="$(date +%s)"
EMAIL="probe+${STAMP}@apalchi-test.com"
PASS="Probe12345!"
SUBJECT="${SUBJECT:-SCIENCE}"

say(){ printf "\n\033[1;35m▶ %s\033[0m\n" "$*"; }
api(){ # api METHOD PATH [json-body]  (uses $TOKEN if set)
  local m="$1" p="$2" body="${3:-}"
  local args=(-sS -X "$m" "$BASE$p" -H 'content-type: application/json')
  [ -n "${TOKEN:-}" ] && args+=(-H "authorization: Bearer $TOKEN")
  [ -n "$body" ] && args+=(--data "$body")
  curl "${args[@]}"
}
unwrap(){ jq -r 'if has("data") then .data else . end'; }

# ── 0. A realistic mock study document ───────────────────────────────────────
DOC="$OUT/mock-notes.txt"
cat > "$DOC" <<'NOTES'
Topic: Forces and Motion (Secondary Physics)

Newton's First Law (the law of inertia): an object stays at rest, or keeps moving
at constant velocity in a straight line, unless a resultant (net) force acts on it.
Inertia is the tendency of an object to resist a change in its state of motion;
more mass means more inertia.

Newton's Second Law: the resultant force on an object equals its mass times its
acceleration, F = m a. Force is measured in newtons (N), mass in kilograms (kg),
acceleration in metres per second squared (m/s^2). A resultant force in the
direction of motion speeds the object up; opposite to motion, it slows it down.

Newton's Third Law: for every action there is an equal and opposite reaction. The
two forces act on DIFFERENT objects, so they never cancel out.

Friction is a force that opposes motion between surfaces in contact. It converts
kinetic energy into heat. Air resistance is friction with air and increases with speed.

Terminal velocity: a falling object accelerates until air resistance equals weight;
the resultant force becomes zero and it falls at a constant (terminal) velocity.

Worked example: a 2 kg trolley is pushed with a resultant force of 6 N. Its
acceleration a = F/m = 6/2 = 3 m/s^2.
NOTES
echo "Mock document: $(wc -w < "$DOC") words → $DOC"

# ── 1. Register, then create a Mochi (onboard/quick currently 500s) ──────────
say "1a. register"
REG="$(api POST /api/v1/auth/register "$(jq -nc --arg e "$EMAIL" --arg p "$PASS" \
  '{email:$e,password:$p,displayName:"Probe Student"}')")"
echo "$REG" > "$OUT/01-register.json"
TOKEN="$(echo "$REG" | unwrap | jq -r '.token // empty')"
[ -z "$TOKEN" ] && { echo "✗ register failed:"; echo "$REG" | jq .; exit 1; }
echo "token=…${TOKEN: -8}"

say "1b. create Mochi (subject=$SUBJECT)"
AV="$(api POST /api/v1/avatars "$(jq -nc --arg s "$SUBJECT" \
  '{name:"Probe Mochi",subject:$s,characterType:"MOCHI"}')")"
echo "$AV" > "$OUT/01b-avatar.json"
AVATAR="$(echo "$AV" | unwrap | jq -r '.id // .avatarId // empty')"
[ -z "$AVATAR" ] && { echo "✗ avatar create failed:"; echo "$AV" | jq .; exit 1; }
echo "avatarId=$AVATAR"

# ── 2. Upload the mock notes ─────────────────────────────────────────────────
say "2. upload mock notes"
curl -sS -X POST "$BASE/api/v1/avatars/$AVATAR/files" \
  -H "authorization: Bearer $TOKEN" -F "file=@$DOC;type=text/plain" > "$OUT/02-upload.json"
jq -c '.' "$OUT/02-upload.json" 2>/dev/null | head -c 400; echo

# ── 3. Compile the wiki (the AI "build the brain" step) ──────────────────────
say "3. compile wiki"
api POST "/api/v1/avatars/$AVATAR/wiki/compile" '{}' > "$OUT/03-compile.json"
jq -c '.' "$OUT/03-compile.json" 2>/dev/null | head -c 400; echo

# ── 4. Poll until the brain is ready ─────────────────────────────────────────
say "4. poll brain status (up to ~120s)"
for i in $(seq 1 24); do
  sleep 5
  A="$(api GET "/api/v1/avatars/$AVATAR" | unwrap)"
  STATE="$(echo "$A" | jq -r '.brainState // "?"')"; PAGES="$(echo "$A" | jq -r '.wikiPageCount // 0')"
  printf "  [%2ds] brainState=%s wikiPages=%s\n" "$((i*5))" "$STATE" "$PAGES"
  [ "$STATE" = "READY" ] && [ "$PAGES" != "0" ] && break
done

# ── 5. The generated content ─────────────────────────────────────────────────
say "5a. wiki pages (compiled knowledge)"
api GET "/api/v1/avatars/$AVATAR/wiki/pages" | unwrap > "$OUT/05-wiki-pages.json"
jq -r '(.pages // .) | (if type=="array" then . else [] end) | .[] | "  • [\(.certainty // "?")] \(.title): \((.content // "") | .[0:160])…"' "$OUT/05-wiki-pages.json" 2>/dev/null | head -20

say "5b. generate modules"
api POST "/api/v1/avatars/$AVATAR/modules/generate" '{}' > "$OUT/06-modules-generate.json"
jq -c '.' "$OUT/06-modules-generate.json" 2>/dev/null | head -c 300; echo
sleep 8
api GET "/api/v1/avatars/$AVATAR/modules" | unwrap > "$OUT/07-modules.json"
jq -r '(if type=="array" then . else (.modules // []) end) | .[] | "  • \(.title // .stage) — stage=\(.stage // "?")"' "$OUT/07-modules.json" 2>/dev/null | head -20

say "5c. daily quiz"
api GET "/api/v1/avatars/$AVATAR/quiz/daily" | unwrap > "$OUT/08-quiz.json"
jq -r '(.questions // .items // .) | (if type=="array" then . else [] end) | .[] | "  Q: \(.question // .prompt // "?")\n     options: \(.options // .choices // [] | join(" | "))\n     answer: \(.answer // .correctAnswer // "?")"' "$OUT/08-quiz.json" 2>/dev/null | head -40

say "DONE — raw JSON in ./$OUT/. avatarId=$AVATAR"
