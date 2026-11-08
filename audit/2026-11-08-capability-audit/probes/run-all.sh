#!/usr/bin/env bash
# 能力审计探针运行器 —— 三元出口码: 0=PASS / 1=RED / 2=NO_DATA
# 用法: ./run-all.sh <probe-java-file> ...
# 每个探针独立进程、独立日志 logs/<name>.log, 汇总写 summary.csv
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
AUDIT="$(dirname "$HERE")"
CP="${PROBE_CLASSPATH:-}"
mkdir -p "$AUDIT/logs"
SUMMARY="$AUDIT/summary.csv"
[ -f "$SUMMARY" ] || echo "probe,exit,verdict" > "$SUMMARY"

for f in "$@"; do
  name=$(basename "$f" .java)
  log="$AUDIT/logs/$name.log"
  echo "=== RUN $name ==="
  timeout 300 java -cp "$CP" "$f" > "$log" 2>&1
  rc=$?
  # 超时按挂死记录(本身是证据), verdict=RED(挂死≠通过)
  if [ $rc -eq 124 ]; then
    echo "TIMEOUT(300s) - probe hung" >> "$log"
    verdict=RED
  else
    v=$(grep -o 'VERDICT: [A-Z_]*' "$log" | head -1 | cut -d' ' -f2)
    case "$rc:$v" in
      0:PASS) verdict=PASS ;;
      1:RED)  verdict=RED ;;
      2:NO_DATA) verdict=NO_DATA ;;
      *) verdict="MISMATCH(rc=$rc,verdict=${v:-none})" ;;
    esac
  fi
  echo "$name,$rc,$verdict" >> "$SUMMARY"
  echo ">>> $name exit=$rc verdict=$verdict (log: $log)"
done
echo "=== SUMMARY ==="
cat "$SUMMARY"
