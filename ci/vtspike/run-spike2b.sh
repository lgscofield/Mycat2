#!/usr/bin/env bash
# I-PR-3 round 2b: FIX the round-2 bounded arm.
# Round 2 used spike.core default 0 -> ThreadPoolExecutor only grows past core when the QUEUE IS FULL,
# so cap=65535 with 2048 tasks never grew past 1 thread (peakInFlight=1). That measured a
# misconfiguration, not a bounded pool. Here core is explicit.
# Also deliberately reproduces the server.json landmine (corePoolSize=1 + maxPendingLimit=65535).
set -u
cd "D:/Workspaces/IdeaProjects/Mycat2" || exit 9

JDK="D:/Program Files/BellSoft/LibericaJDK-21-Full"
JAR="server/target/server-1.22-release-jar-with-dependencies.jar"
WS="ci/vtspike"
OUT="$WS/classes"
LOGS="$WS/logs2b"
mkdir -p "$OUT" "$LOGS"

echo "=== backend readiness ==="
docker exec m1-mysql-a mysqladmin ping -uroot -p123456 --silent >/dev/null 2>&1 || { echo "ABORT backend not ready"; exit 2; }
echo "BACKEND_READY"

SUMMARY="$WS/summary2b.tsv"
printf "tag\tmode\tlock\tC\tcore\tmax\tqueue\tpool\tops\terrors\trejected\twallMs\tqps\tp50ms\tp95ms\tp99ms\tstartP50ms\tstartP95ms\te2eP50ms\te2eP95ms\te2eP99ms\tpeakPlatformThreads\tpeakInFlight\tpinnedFrames\tfinished\n" > "$SUMMARY"
POOL=16
ROWS=200

run() {
  TAG="$1"; MODE="$2"; LOCK="$3"; C="$4"; CORE="$5"; MAX="$6"; QUEUE="$7"
  ITER=$(( 2048 / C ))
  LOG="$LOGS/${TAG}.log"
  echo "--- RUN $TAG mode=$MODE lock=$LOCK C=$C core=$CORE max=$MAX queue=$QUEUE iter=$ITER ---"
  "$JDK/bin/java" -Djdk.tracePinnedThreads=full \
    -Dspike.core="$CORE" -Dspike.max="$MAX" -Dspike.queue="$QUEUE" \
    -cp "$JAR;$OUT" VtSpike "$MODE" "$LOCK" "$C" "$ITER" "$ROWS" "$POOL" \
    > "$LOG" 2>&1
  RC=$?
  LINE=$(grep -m1 '^RESULT ' "$LOG" || true)
  PIN=$(grep -c '<== monitors' "$LOG" || true)
  if [ -z "$LINE" ]; then
    echo "NO_RESULT rc=$RC tag=$TAG"; tail -5 "$LOG"
    printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\tNO_RESULT\n" "$TAG" "$MODE" "$LOCK" "$C" "$CORE" "$MAX" "$QUEUE" "$POOL" >> "$SUMMARY"
    return
  fi
  echo "$LINE  pinnedFrames=$PIN"
  {
    printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s" "$TAG" "$MODE" "$LOCK" "$C" "$CORE" "$MAX" "$QUEUE" "$POOL"
    for k in ops errors rejected wallMs qps p50ms p95ms p99ms startP50ms startP95ms e2eP50ms e2eP95ms e2eP99ms peakPlatformThreads peakInFlight; do
      printf "\t%s" "$(echo "$LINE" | tr ' ' '\n' | grep -m1 "^$k=" | cut -d= -f2-)"
    done
    printf "\t%s\t%s\n" "$PIN" "$(echo "$LINE" | tr ' ' '\n' | grep -m1 '^finished=' | cut -d= -f2-)"
  } >> "$SUMMARY"
}

# sane bounded pool: core == max, deep queue
run b-core64-reentrant-C64   bounded reentrant 64   64 64 65535
run b-core64-reentrant-C512  bounded reentrant 512  64 64 65535
run b-core64-reentrant-C2048 bounded reentrant 2048 64 64 65535
run b-core64-none-C2048      bounded none      2048 64 64 65535
# clean control: does `synchronized` cost a PLATFORM pool anything? (isolates VT-specific pinning)
run b-core64-sync-C2048      bounded sync      2048 64 64 65535
# thread-count sweep with core properly set
run b-core16-reentrant-C2048 bounded reentrant 2048 16 64 65535
run b-core256-reentrant-C2048 bounded reentrant 2048 256 256 65535
# THE server.json LANDMINE, deliberate: corePoolSize=1, maxPoolSize=1024, maxPendingLimit=65535
run b-core1-max1024-C2048    bounded reentrant 2048 1 1024 65535
# rejection with a properly-sized core: shallow queue -> hard reject
run b-core64q8-reentrant-C2048 bounded reentrant 2048 64 64 8

echo "=== SUMMARY2B ==="
cat "$SUMMARY"
echo "SPIKE2B_DONE"