#!/usr/bin/env bash
# G8-A 分批断点续跑器: 每批 8 个测试类,单批 5-15 分钟内完成(杀不死形态);
# done-list 断点续跑;结果逐批入 RESULTS。出口 0=全部批完成。
set -u
cd /d/Workspaces/Lingma/mycat2
A="D:/Workspaces/Lingma/mycat2/audit/2026-11-08-capability-audit"
B="$A/batches"
DONE="$B/done.txt"
touch "$DONE"
export JAVA_HOME="D:\Program Files\BellSoft\LibericaJDK-21-Full"
export PATH="$JAVA_HOME/bin:$PATH"
for bf in "$B"/b-*.txt; do
  name=$(basename "$bf" .txt)
  grep -qx "$name" "$DONE" && { echo "SKIP $name (done)"; continue; }
  classes=$(paste -sd, "$bf")
  echo "=== BATCH $name START $(date +%H:%M:%S): $classes ==="
  if mvn -q -B -pl example test -Dtest="$classes" -Dsurefire.failIfNoSpecifiedTests=false \
      "-Ddb_mycat=jdbc:mysql://localhost:8068/dbaudit_a?username=root&password=123456&useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true" \
      "-Ddb1=jdbc:mysql://localhost:3307/mysql?username=root&password=123456&useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true" \
      "-Ddb2=jdbc:mysql://localhost:3309/mysql?username=root&password=123456&useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true" \
      > "$A/logs/G8-$name.log" 2>&1; then
    echo "$name" >> "$DONE"
    echo "=== BATCH $name PASS ==="
  else
    echo "=== BATCH $name RED/ERR (续跑时重试或定性) ==="
    grep -aE "Tests run: [0-9]+, Fail.*Skipped: [0-9]+$" "$A/logs/G8-$name.log" | tail -1
    echo "$name" >> "$DONE"   # RED 也记完成(定性阶段处理), 防死循环
  fi
done
echo "=== ALL BATCHES DONE $(date +%H:%M:%S) ==="
echo "汇总:"; grep -ahE "Tests run: [0-9]+, Fail.*Skipped: [0-9]+$" "$A/logs/G8-b-*.log" | awk -F'[:,]' '{t+=$2;f+=$4;e+=$6;s+=$8} END{print "TOTAL tests="t" failures="f" errors="e" skipped="s}'
