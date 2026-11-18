#!/usr/bin/env bash
# I-PR-3 round 3: does Druid 1.2.28 / Connector/J 9.3.0 pin a carrier on their OWN,
# with no Mycat lock in the path? Saturated borrow (C >> pool) is the case VtSpike never exercised.
set -u
cd "D:/Workspaces/IdeaProjects/Mycat2" || exit 9

JDK="D:/Program Files/BellSoft/LibericaJDK-21-Full"
JAR="server/target/server-1.22-release-jar-with-dependencies.jar"
WS="ci/vtspike"
OUT="$WS/classes"
LOGS="$WS/logs3"
mkdir -p "$OUT" "$LOGS"

echo "=== backend readiness ==="
docker exec m1-mysql-a mysqladmin ping -uroot -p123456 --silent >/dev/null 2>&1 || { echo "ABORT backend not ready"; exit 2; }
echo "BACKEND_READY"

echo "=== compile DruidPinProbe ==="
"$JDK/bin/javac" -cp "$JAR" -d "$OUT" "$WS/DruidPinProbe.java" 2>&1 | head -20
[ -f "$OUT/DruidPinProbe.class" ] || { echo "ABORT compile failed"; exit 2; }
echo "COMPILE_OK"

SUMMARY="$WS/summary3.tsv"
printf "tag\tmode\tC\tpool\tops\terrors\twallMs\tqps\tborrowP50ms\tborrowP95ms\tp50ms\tp95ms\tp99ms\tpeakPlatformThreads\tpeakInFlight\tpinnedFrames\tfinished\n" > "$SUMMARY"
ROWS=200

run() {
  TAG="$1"; MODE="$2"; C="$3"; POOL="$4"
  ITER=$(( 2048 / C ))
  LOG="$LOGS/${TAG}.log"
  echo "--- RUN $TAG mode=$MODE C=$C pool=$POOL iter=$ITER ---"
  "$JDK/bin/java" -Djdk.tracePinnedThreads=full -cp "$JAR;$OUT" \
    DruidPinProbe "$MODE" "$C" "$ITER" "$POOL" "$ROWS" > "$LOG" 2>&1
  RC=$?
  LINE=$(grep -m1 '^RESULT ' "$LOG" || true)
  PIN=$(grep -c '<== monitors' "$LOG" || true)
  if [ -z "$LINE" ]; then
    echo "NO_RESULT rc=$RC tag=$TAG"; tail -5 "$LOG"
    printf "%s\t%s\t%s\t%s\tNO_RESULT\n" "$TAG" "$MODE" "$C" "$POOL" >> "$SUMMARY"
    return
  fi
  echo "$LINE  pinnedFrames=$PIN"
  {
    printf "%s\t%s\t%s\t%s" "$TAG" "$MODE" "$C" "$POOL"
    for k in ops errors wallMs qps borrowP50ms borrowP95ms p50ms p95ms p99ms peakPlatformThreads peakInFlight; do
      printf "\t%s" "$(echo "$LINE" | tr ' ' '\n' | grep -m1 "^$k=" | cut -d= -f2-)"
    done
    printf "\t%s\t%s\n" "$PIN" "$(echo "$LINE" | tr ' ' '\n' | grep -m1 '^finished=' | cut -d= -f2-)"
  } >> "$SUMMARY"
}

run vt-C64-pool16     vt     64  16
run cached-C64-pool16 cached 64  16
run vt-C512-pool16    vt     512 16
run cached-C512-pool16 cached 512 16
run vt-C512-pool4     vt     512 4
run cached-C512-pool4 cached 512 4

echo "=== SUMMARY3 ==="
cat "$SUMMARY"
echo "=== pinning marker hotspots (vt runs, no Mycat lock in path) ==="
grep -h '<== monitors' "$LOGS"/vt-*.log 2>/dev/null | sed 's/^[[:space:]]*//' | sort | uniq -c | sort -rn | head -20
echo "=== druid/mysql frames inside pinned traces ==="
grep -h -E '^\s+(com\.alibaba\.druid|com\.mysql)' "$LOGS"/vt-*.log 2>/dev/null | sed 's/^[[:space:]]*//' | sort | uniq -c | sort -rn | head -20
echo "SPIKE3_DONE"