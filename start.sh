#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Pally Backend — canonical start script
# Run from pally-backend/ directory.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ── 0. Load .env if present (never commit .env to git) ───────────────────────
if [[ -f "$SCRIPT_DIR/.env" ]]; then
  set -a; source "$SCRIPT_DIR/.env"; set +a
fi

# ── 1. Validate required env vars ────────────────────────────────────────────
if [[ -z "${CLAUDE_API_KEY:-}" ]]; then
  echo "ERROR: CLAUDE_API_KEY is not set."
  echo "  Either: export CLAUDE_API_KEY=sk-ant-..."
  echo "  Or add it to pally-backend/.env (already gitignored)"
  exit 1
fi

# ── 2. Check Docker + pally-postgres container is running ────────────────────
if ! docker info > /dev/null 2>&1; then
  echo "ERROR: Docker is not running. Start Docker Desktop first."
  exit 1
fi

if ! docker ps --filter "name=pally-postgres" --filter "status=running" | grep -q pally-postgres; then
  echo "pally-postgres container is not running — starting it now..."
  cd "$(dirname "$0")" && docker compose up -d postgres
  echo "Waiting for postgres to be healthy..."
  until docker inspect pally-postgres --format='{{.State.Health.Status}}' 2>/dev/null | grep -q healthy; do
    sleep 1
  done
  echo "postgres is healthy"
fi

# ── 3. Kill anything already on port 8080 ────────────────────────────────────
PIDS=$(lsof -ti:8080 2>/dev/null || true)
if [[ -n "$PIDS" ]]; then
  echo "Killing existing process(es) on port 8080: $PIDS"
  kill -9 $PIDS
  sleep 1
fi

# ── 4. Find JAR ───────────────────────────────────────────────────────────────
JAR=$(ls "$SCRIPT_DIR"/build/libs/pally-backend*.jar 2>/dev/null | head -1)
if [[ -z "$JAR" ]]; then
  echo "No JAR found — building now..."
  cd "$SCRIPT_DIR" && ./gradlew bootJar --no-daemon -q
  JAR=$(ls "$SCRIPT_DIR"/build/libs/pally-backend*.jar | head -1)
fi

echo ""
echo "─────────────────────────────────────────────────"
echo "  Starting Pally Backend"
echo "  JAR    : $JAR"
echo "  DB     : jdbc:postgresql://localhost:5434/pally"
echo "  Port   : 8080"
echo "  Claude : ${CLAUDE_API_KEY:0:20}..."
echo "─────────────────────────────────────────────────"
echo ""

# ── 5. Start backend ──────────────────────────────────────────────────────────
nohup java -jar "$JAR" \
  --spring.datasource.url=jdbc:postgresql://localhost:5434/pally \
  --spring.datasource.username=pally \
  --spring.datasource.password=pally \
  --storage.type=local \
  > /tmp/backend.log 2>&1 &

echo "Backend PID: $!"
echo "Logs: tail -f /tmp/backend.log"
echo ""

# ── 6. Wait for health ───────────────────────────────────────────────────────
echo -n "Waiting for backend to start"
for i in $(seq 1 30); do
  if curl -sf http://localhost:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
    echo ""
    echo "Backend is UP ✓"
    curl -s http://localhost:8080/actuator/health
    echo ""
    exit 0
  fi
  echo -n "."
  sleep 1
done

echo ""
echo "ERROR: Backend did not start in 30s. Check logs:"
echo "  tail -50 /tmp/backend.log | grep -E 'ERROR|Exception|Caused'"
exit 1
