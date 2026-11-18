#!/usr/bin/env bash
# I-PR-3 spike round 2: adds the BOUNDED platform-pool arm (the real I-PR-1 candidate),
# a rejection arm, variance repeats, and submit->start delay so pinned-VT queueing is visible.
# Zero production code change.
set -u
cd "D:/Workspaces/IdeaProjects/Mycat2" || exit 9

JDK="D:/Program Files/BellSoft/LibericaJDK-21-Full"
JAR="server/target/server-1.22-release-jar-with-dependencies.jar"
WS="ci/vtspike"
OUT="$WS/classes"
LOGS="$WS/logs2"
mkdir -p "$OUT" "$LOGS"

echo "=== JDK ==="
"$JDK/bin/java" -version 2>&1 | head -1

echo "=== backend readiness ==="
for i in $(seq 1 60); do
  if docker exec m1-mysql-a mysqladmin ping -uroot -p123456 --silent >/dev/null 2>&1; then
    echo "BACKEND_READY attempt=$i"; break
  fi
  sleep 2
done
docker exec m1-mysql-a mysqladmin ping -uroot -p123456 --silent >/dev/null 2>&1 || { echo "ABORT backend not ready"; exit 2; }

echo "=== compile probe (round2) ==="
"$JDK/bin/javac" -cp "$JAR" -d "$OUT" "$WS/VtSpike.java" 2>&1 | grep -v "^ע" | head -20
[ -f "$OUT/VtSpike.class" ] || { echo "ABORT compile failed"; exit 2; }
echo "COMPILE_OK"

SUMMARY="$WS/summary2.tsv"
COLS="tag mode lock C max queue pool ops errors rejected wallMs qps p50ms p95ms p99ms startP50ms startP95ms e2eP50ms e2eP95ms e2eP99ms peakPlatformThreads peakInFlight pinnedFrames finished"
printf "%s\n" "$(echo "$COLS" | tr ' ' '\t')" > "$SUMMARY"

POOL=16
ROWS=200

run() {
  TAG="$1"; MODE="$2"; LOCK="$3"; C="$4"; MAX="$5"; QUEUE="$6"
  ITER=$(( 2048 / C ))
  LOG="$LOGS/${TAG}.log"
  echo "--- RUN $TAG mode=$MODE lock=$LOCK C=$C max=$MAX queue=$QUEUE iter=$ITER ---"
  "$JDK/bin/java" -Djdk.tracePinnedThreads=full \
    -Dspike.max="$MAX" -Dspike.queue="$QUEUE" \
    -cp "$JAR;$OUT" VtSpike "$MODE" "$LOCK" "$C" "$ITER" "$ROWS" "$POOL" \
    > "$LOG" 2>&1
  RC=$?
  LINE=$(grep -m1 '^RESULT ' "$LOG" || true)
  PIN=$(grep -c '<== monitors' "$LOG" || true)
  if [ -z "$LINE" ]; then
    echo "NO_RESULT rc=$RC tag=$TAG"; tail -5 "$LOG"
    printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\tNO_RESULT\n" "$TAG" "$MODE" "$LOCK" "$C" "$MAX" "$QUEUE" "$POOL" >> "$SUMMARY"
    return
  fi
  echo "$LINE  pinnedFrames=$PIN"
  {
    printf "%s\t%s\t%s\t%s\t%s\t%s\t%s" "$TAG" "$MODE" "$LOCK" "$C" "$MAX" "$QUEUE" "$POOL"
    for k in ops errors rejected wallMs qps p50ms p95ms p99ms startP50ms startP95ms e2eP50ms e2eP95ms e2eP99ms peakPlatformThreads peakInFlight; do
      printf "\t%s" "$(echo "$LINE" | tr ' ' '\n' | grep -m1 "^$k=" | cut -d= -f2-)"
    done
    printf "\t%s\t%s\n" "$PIN" "$(echo "$LINE" | tr ' ' '\n' | grep -m1 '^finished=' | cut -d= -f2-)"
  } >> "$SUMMARY"
}

# bounded arm: the I-PR-1 candidate shape (max threads + deep queue), locks already converted
run bounded64-reentrant-C64    bounded reentrant 64   64 65535
run bounded64-reentrant-C512   bounded reentrant 512  64 65535
run bounded64-reentrant-C2048  bounded reentrant 2048 64 65535
run bounded64-none-C2048       bounded none      2048 64 65535
# thread-count sweep at fixed C: where does bounding start to cost throughput?
run bounded16-reentrant-C2048  bounded reentrant 2048 16 65535
run bounded256-reentrant-C2048 bounded reentrant 2048 256 65535
# control: does `synchronized` hurt a PLATFORM pool too, or is the cost VT-specific?
run bounded64-sync-C2048       bounded sync      2048 64 65535
# rejection arm: shallow queue -> the failure mode I-PR-1's release lane must never hit
run bounded16q8-reentrant-C2048 bounded reentrant 2048 16 8
# variance repeats + startDelay visibility for the pinned arm
run rep-cached-reentrant-C2048 cached  reentrant 2048 0 0
run rep-vt-reentrant-C2048     vt      reentrant 2048 0 0
run rep-vt-sync-C2048          vt      sync      2048 0 0

echo "=== SUMMARY2 ==="
cat "$SUMMARY"
echo "=== pinning hotspots (round2 vt runs) ==="
grep -h '<== monitors' "$LOGS"/*vt*.log 2>/dev/null | sed 's/^[[:space:]]*//' | sort | uniq -c | sort -rn | head -25
echo "SPIKE2_DONE"