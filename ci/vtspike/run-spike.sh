#!/usr/bin/env bash
# I-PR-3 throwaway spike runner. Zero production code change.
set -u
cd "D:/Workspaces/IdeaProjects/Mycat2" || exit 9

JDK="D:/Program Files/BellSoft/LibericaJDK-21-Full"
JAR="server/target/server-1.22-release-jar-with-dependencies.jar"
WS="ci/vtspike"
OUT="$WS/classes"
LOGS="$WS/logs"
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

echo "=== compile probe ==="
"$JDK/bin/javac" -cp "$JAR" -d "$OUT" "$WS/VtSpike.java" 2>&1 | head -20
[ -f "$OUT/VtSpike.class" ] || { echo "ABORT compile failed"; exit 2; }
echo "COMPILE_OK"

SUMMARY="$WS/summary.tsv"
printf "mode\tlock\tC\tpool\trows\tops\terrors\twallMs\tqps\tp50ms\tp95ms\tp99ms\tpeakPlatformThreads\tpeakInFlight\tpinnedFrames\tfinished\n" > "$SUMMARY"

POOL=16
ROWS=200
for MODE in cached vt; do
  for LOCK in sync reentrant none; do
    for C in 64 512 2048; do
      ITER=$(( 2048 / C ))
      TAG="${MODE}-${LOCK}-C${C}"
      LOG="$LOGS/${TAG}.log"
      echo "--- RUN $TAG (iter=$ITER) ---"
      "$JDK/bin/java" -Djdk.tracePinnedThreads=full \
        -cp "$JAR;$OUT" VtSpike "$MODE" "$LOCK" "$C" "$ITER" "$ROWS" "$POOL" \
        > "$LOG" 2>&1
      RC=$?
      LINE=$(grep -m1 '^RESULT ' "$LOG" || true)
      PIN=$(grep -c '<== monitors' "$LOG" 2>/dev/null || echo 0)
      if [ -z "$LINE" ]; then
        echo "NO_RESULT rc=$RC tag=$TAG (tail below)"
        tail -5 "$LOG"
        printf "%s\t%s\t%s\t%s\t%s\tNO_RESULT\t-\t-\t-\t-\t-\t-\t-\t-\t%s\t-\n" \
          "$MODE" "$LOCK" "$C" "$POOL" "$ROWS" "$PIN" >> "$SUMMARY"
        continue
      fi
      echo "$LINE  pinnedFrames=$PIN"
      get() { echo "$LINE" | tr ' ' '\n' | grep -m1 "^$1=" | cut -d= -f2-; }
      printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
        "$MODE" "$LOCK" "$C" "$POOL" "$ROWS" \
        "$(get ops)" "$(get errors)" "$(get wallMs)" "$(get qps)" \
        "$(get p50ms)" "$(get p95ms)" "$(get p99ms)" \
        "$(get peakPlatformThreads)" "$(get peakInFlight)" "$PIN" "$(get finished)" >> "$SUMMARY"
    done
  done
done

echo "=== SUMMARY ==="
cat "$SUMMARY"
echo "=== pinning frame hotspots (vt runs) ==="
grep -h '<== monitors' "$LOGS"/vt-*.log 2>/dev/null | sed 's/^[[:space:]]*//' | sort | uniq -c | sort -rn | head -25
echo "SPIKE_DONE"