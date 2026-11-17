#!/usr/bin/env bash
# I-PR-3 round 2c: confirmation repeats for the two numbers the ruling rests on.
# Round 2b showed large run-to-run variance (core64: C64=334.6, C512=264.2, C2048=657.9 qps),
# so the decisive arms are re-run twice each, interleaved, same config.
set -u
cd "D:/Workspaces/IdeaProjects/Mycat2" || exit 9

JDK="D:/Program Files/BellSoft/LibericaJDK-21-Full"
JAR="server/target/server-1.22-release-jar-with-dependencies.jar"
WS="ci/vtspike"
OUT="$WS/classes"
LOGS="$WS/logs2c"
mkdir -p "$OUT" "$LOGS"

docker exec m1-mysql-a mysqladmin ping -uroot -p123456 --silent >/dev/null 2>&1 || { echo "ABORT backend not ready"; exit 2; }
echo "BACKEND_READY"

SUMMARY="$WS/summary2c.tsv"
printf "tag\tmode\tlock\tC\tcore\tmax\tqueue\tpool\tops\terrors\trejected\twallMs\tqps\tp50ms\tp95ms\tp99ms\tstartP50ms\tstartP95ms\te2eP50ms\te2eP95ms\te2eP99ms\tpeakPlatformThreads\tpeakInFlight\tpinnedFrames\tfinished\n" > "$SUMMARY"
POOL=16
ROWS=200

run() {
  TAG="$1"; MODE="$2"; LOCK="$3"; C="$4"; CORE="$5"; MAX="$6"; QUEUE="$7"
  ITER=$(( 2048 / C ))
  LOG="$LOGS/${TAG}.log"
  echo "--- RUN $TAG mode=$MODE lock=$LOCK C=$C core=$CORE max=$MAX queue=$QUEUE ---"
  "$JDK/bin/java" -Djdk.tracePinnedThreads=full \
    -Dspike.core="$CORE" -Dspike.max="$MAX" -Dspike.queue="$QUEUE" \
    -cp "$JAR;$OUT" VtSpike "$MODE" "$LOCK" "$C" "$ITER" "$ROWS" "$POOL" > "$LOG" 2>&1
  LINE=$(grep -m1 '^RESULT ' "$LOG" || true)
  PIN=$(grep -c '<== monitors' "$LOG" || true)
  if [ -z "$LINE" ]; then
    echo "NO_RESULT tag=$TAG"; tail -5 "$LOG"
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

# interleaved repeats so host-load drift cannot systematically favour one arm
for R in 1 2; do
  run "r${R}-bcore16-C2048"  bounded reentrant 2048 16 64 65535
  run "r${R}-vt-C2048"       vt      reentrant 2048 0  0  0
  run "r${R}-bcore64-C2048"  bounded reentrant 2048 64 64 65535
  run "r${R}-cached-C2048"   cached  reentrant 2048 0  0  0
done
# and the landmine once more, to show it is deterministic not load-dependent
run r3-bcore1max1024-C2048 bounded reentrant 2048 1 1024 65535

echo "=== SUMMARY2C ==="
cat "$SUMMARY"
echo "SPIKE2C_DONE"