#!/usr/bin/env bash
# Weakness-head smoke probe. Verifies the pilot endpoints are live and reports
# whether the pilot flag (weakness.profile.enabled) is ON in the target env.
#
#   BASE=https://pallybackend-production.up.railway.app ./scripts/weakness_smoke.sh
#
# When the flag is OFF this prints enabled=false (correct dormant state).
# Flip it by setting weakness.profile.enabled=true in the Railway env, redeploy,
# then re-run — enabled becomes true and, once a student has completed PROVE with
# some wrong answers, /weakness/focus returns focusAreas + recentWins.
set -euo pipefail
BASE="${BASE:-https://pallybackend-production.up.railway.app}"

api() {
  local m=$1 p=$2 body=${3:-}
  local args=(-s -X "$m" "$BASE$p" -H 'content-type: application/json')
  [ -n "${TOKEN:-}" ] && args+=(-H "authorization: Bearer $TOKEN")
  [ -n "$body" ] && args+=(--data "$body")
  curl "${args[@]}"
}
unwrap() { jq -c '.data // .'; }

echo "== weakness smoke @ $BASE =="

# 1) Route liveness (unauth → 401 proves the route is deployed, not 404).
CODE="$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/v1/weakness/focus?subject=MATHS")"
echo "route /weakness/focus unauth status: $CODE (expect 401/403)"
[ "$CODE" = "404" ] && { echo "❌ route not deployed"; exit 1; }

# 2) Authenticated read.
EMAIL="weak-probe-$(date +%s)@example.com"
REG="$(api POST /api/v1/auth/register \
  "$(jq -nc --arg e "$EMAIL" '{email:$e,password:"Probe123!",displayName:"Weak Probe",birthYear:1995}')")"
TOKEN="$(echo "$REG" | unwrap | jq -r '.token // empty')"
[ -z "$TOKEN" ] && { echo "❌ register failed: $REG"; exit 1; }

FOCUS="$(api GET '/api/v1/weakness/focus?subject=MATHS')"
ENABLED="$(echo "$FOCUS" | unwrap | jq -r '.enabled')"
AREAS="$(echo "$FOCUS" | unwrap | jq -r '.focusAreas | length')"
WINS="$(echo "$FOCUS" | unwrap | jq -r '.recentWins | length')"
echo "focus: enabled=$ENABLED focusAreas=$AREAS recentWins=$WINS"

if [ "$ENABLED" = "true" ]; then
  echo "✅ PILOT ON — weakness loop active. focusAreas/recentWins populate as students PROVE."
else
  echo "⏸️  PILOT OFF (dormant, as expected). To activate: set weakness.profile.enabled=true"
  echo "    in Railway → redeploy → re-run this probe."
fi
