#!/usr/bin/env bash
# PROBE: P-E-03 old-connector(Connector/J 5.1.49) 冒烟
# 复用仓库 ci/OldConnectorProbe.java; jar 按坐标取本地 m2(缺则 dependency:get 下载)
LOG="D:/Workspaces/Lingma/mycat2/audit/2026-11-08-capability-audit/logs/P-E-03.log"
JDK="D:/Program Files/BellSoft/LibericaJDK-21-Full"
M2="$HOME/.m2/repository/mysql/mysql-connector-java/5.1.49/mysql-connector-java-5.1.49.jar"
if [ ! -f "$M2" ]; then
  echo "downloading 5.1.49 via mvn dependency:get..." > "$LOG"
  mvn -q -B dependency:get -Dartifact=mysql:mysql-connector-java:5.1.49 >> "$LOG" 2>&1
fi
if [ ! -f "$M2" ]; then echo "NO_DATA: old connector jar 不可得" >> "$LOG"; cat "$LOG"; echo "VERDICT: NO_DATA"; exit 2; fi
{
echo "--- run ci/OldConnectorProbe.java with 5.1.49 ---"
"$JDK/bin/java" -cp "$M2" "D:/Workspaces/Lingma/mycat2/ci/OldConnectorProbe.java" 2>&1
echo "probe rc=$?"
} >> "$LOG" 2>&1
tail -25 "$LOG"
if grep -qE "PASS|pass|ok|OK" "$LOG" && ! grep -qE "FAIL|Exception in thread" "$LOG"; then echo "VERDICT: PASS"; exit 0; fi
if grep -qE "FAIL" "$LOG"; then echo "VERDICT: RED"; exit 1; fi
echo "VERDICT: RED(输出无法判定, 人工看日志)"; exit 1
