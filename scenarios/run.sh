#!/usr/bin/env bash
# Замер задания 6: сервисы, снимки базы памяти и прогоны hermes -p l78 chat -q … -Q.
#
#   run.sh start-api | stop-api          wardrobe-api на 8090
#   run.sh start-mem [limit] | stop-mem  сервис памяти на 8091 (база scenarios/data/memory.db)
#   run.sh restore <empty|S1|S2|S3>      живая база ← снимок (сервис памяти должен быть остановлен)
#   run.sh snapshot <S1|S2|S3>           снимок ← живая база (сервис памяти должен быть остановлен)
#   run.sh chat <имя-прогона> "<текст>"  прогон Hermes; итог в scenarios/out/<имя-прогона>/
#   run.sh recall "<текст>"              POST /recall
#   run.sh facts                         все факты живой базы, со статусами
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DATA="$ROOT/scenarios/data"
OUT="$ROOT/scenarios/out"
LIVE_DB="$DATA/memory.db"
MEM_LOG="$DATA/memory-service.log"
API_LOG="$DATA/wardrobe-api.log"
MCP_LOG="${TMPDIR%/}/wardrobe-mcp.log"
PROFILE_ENV="$HOME/.hermes/profiles/l78/.env"
HERMES="${HERMES:-$HOME/.local/bin/hermes}"
MEM_JAR="$ROOT/wardrobe-memory/target/wardrobe-memory-1.0-SNAPSHOT.jar"
API_JAR="$ROOT/wardrobe-api/target/wardrobe-api-1.0-SNAPSHOT.jar"
MEM_URL="http://127.0.0.1:8091"
API_URL="http://127.0.0.1:8090"
USER_ID="me"

mkdir -p "$DATA" "$OUT"

pid_on_port() { lsof -t -nP -iTCP:"$1" -sTCP:LISTEN 2>/dev/null || true; }

wait_listening() {
  for _ in $(seq 1 60); do
    [ -n "$(pid_on_port "$1")" ] && return 0
    sleep 1
  done
  echo "порт $1 не поднялся за 60 с" >&2
  return 1
}

stop_port() {
  local pid
  pid="$(pid_on_port "$1")"
  [ -z "$pid" ] && { echo "на порту $1 ничего нет"; return 0; }
  kill $pid
  for _ in $(seq 1 30); do
    [ -z "$(pid_on_port "$1")" ] && { echo "остановлен процесс на порту $1"; return 0; }
    sleep 1
  done
  echo "процесс на порту $1 не остановился" >&2
  return 1
}

require_mem_stopped() {
  [ -z "$(pid_on_port 8091)" ] || { echo "сначала run.sh stop-mem" >&2; exit 1; }
}

lines() { [ -f "$1" ] && wc -l < "$1" | tr -d ' ' || echo 0; }

case "${1:-}" in
  start-api)
    [ -n "$(pid_on_port 8090)" ] && { echo "wardrobe-api уже на 8090"; exit 0; }
    nohup java -jar "$API_JAR" >>"$API_LOG" 2>&1 &
    wait_listening 8090
    echo "wardrobe-api запущен, items: $(curl -s "$API_URL/api/items" | jq '.items | length')"
    ;;
  stop-api)
    stop_port 8090
    ;;
  start-mem)
    limit="${2:-5}"
    [ -n "$(pid_on_port 8091)" ] && { echo "сервис памяти уже на 8091" >&2; exit 1; }
    key="$(grep '^OPENROUTER_API_KEY=' "$PROFILE_ENV" | cut -d= -f2-)"
    echo "=== start-mem $(date -u +%FT%TZ) limit=$limit db=$LIVE_DB" >>"$MEM_LOG"
    MEMORY_DB_PATH="$LIVE_DB" MEMORY_RECALL_LIMIT="$limit" EXTRACTOR_API_KEY="$key" \
      nohup java -jar "$MEM_JAR" >>"$MEM_LOG" 2>&1 &
    wait_listening 8091
    echo "сервис памяти запущен, recall-limit=$limit"
    ;;
  stop-mem)
    stop_port 8091
    ;;
  restore)
    require_mem_stopped
    rm -f "$LIVE_DB" "$LIVE_DB-journal" "$LIVE_DB-wal" "$LIVE_DB-shm"
    if [ "${2:?empty|S1|S2|S3}" = "empty" ]; then
      echo "живая база очищена"
    else
      cp "$DATA/$2.db" "$LIVE_DB"
      echo "живая база ← снимок $2"
    fi
    ;;
  snapshot)
    require_mem_stopped
    cp "$LIVE_DB" "$DATA/${2:?S1|S2|S3}.db"
    echo "снимок $2 ← живая база"
    ;;
  chat)
    run="${2:?имя прогона}"
    text="${3:?текст}"
    dir="$OUT/$run"
    mkdir -p "$dir"
    mem_from=$(( $(lines "$MEM_LOG") + 1 ))
    mcp_from=$(( $(lines "$MCP_LOG") + 1 ))
    {
      echo "run: $run"
      echo "input: $text"
      echo "started: $(date -u +%FT%TZ)"
      echo "memory_service: $([ -n "$(pid_on_port 8091)" ] && echo up || echo down)"
    } >"$dir/meta.txt"
    # Пустая рабочая папка: иначе агент терминалом читает файлы замера (прогон 12, первая попытка).
    workdir="$(mktemp -d)"
    echo "cwd: $workdir" >>"$dir/meta.txt"
    "$HERMES" -p l78 chat --in "$workdir" -q "$text" -Q >"$dir/answer.txt" 2>"$dir/stderr.txt" || true
    if [ -n "$(pid_on_port 8091)" ] && tail -n +"$mem_from" "$MEM_LOG" | grep -q 'msg=close\|close turn_id'; then
      for _ in $(seq 1 90); do
        tail -n +"$mem_from" "$MEM_LOG" | grep -q 'extract done\|extract failed\|extract skip' && break
        sleep 1
      done
    fi
    tail -n +"$mem_from" "$MEM_LOG" >"$dir/memory-service.log" 2>/dev/null || true
    tail -n +"$mcp_from" "$MCP_LOG" >"$dir/wardrobe-mcp.log" 2>/dev/null || true
    echo "finished: $(date -u +%FT%TZ)" >>"$dir/meta.txt"
    echo "--- $run"
    grep -o 'recall user_id=[^ ]* ids=\[[^]]*\]' "$dir/memory-service.log" || echo "recall: нет строки (сервис памяти не отвечал)"
    grep -oE 'excludeType=\[[^]]*\]( excludeColor=\[[^]]*\])?( excludeSeason=\[[^]]*\])?' "$dir/wardrobe-mcp.log" || echo "wardrobe_search: не вызывался"
    grep -oE '(accept|reject|extract skip|extract failed) .*|forget fact_id=[^ ]*|remember fact_id=[^ ]*' "$dir/memory-service.log" || true
    ;;
  recall)
    jq -n --arg u "$USER_ID" --arg q "${2:?текст}" '{user_id: $u, q: $q}' \
      | curl -s -X POST "$MEM_URL/recall" -H 'Content-Type: application/json' -d @- | jq .
    ;;
  facts)
    sqlite3 -readonly -header -column "$LIVE_DB" \
      "SELECT id, kind, scope, standing, status, supersedes, fact, quote, forgotten_at FROM fact ORDER BY id;"
    ;;
  *)
    sed -n '2,10p' "$0"
    exit 1
    ;;
esac
