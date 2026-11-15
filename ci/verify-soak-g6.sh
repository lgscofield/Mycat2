#!/usr/bin/env bash
# I-DEBT-96/G6 长稳护栏(CI 版): 健康实例 + 30 分钟混合 CRUD 长稳 + 连接不泄漏断言。
# 前置: mysql-a(3306)/mysql-b(3307) services root/123456(CI 缺省)。
# 出口: 0=PASS / 1=RED / 2=NO_DATA。
set -euo pipefail

JAR="server/target/server-1.22-release-jar-with-dependencies.jar"
[ -f "$JAR" ] || { echo "RESULT=NO_DATA (jar 缺失)"; exit 2; }

# 静音实例日志(DEBUG→WARN),防 30 分钟日志洪泛拖慢
printf '<configuration><appender name="C" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%%d[%%-5level]%%logger{20}:%%msg%%n</pattern></encoder></appender><root level="WARN"><appender-ref ref="C"/></root></configuration>' > /tmp/logback-quiet.xml

# 独占 8066 的残留实例清场
PID=$(ss -tlnp 2>/dev/null | grep ':8066' | grep -oE 'pid=[0-9]+' | head -1 | grep -oE '[0-9]+' || true)
[ -n "$PID" ] && kill -9 "$PID" 2>/dev/null || true
sleep 2

# 独立长稳实例(8066,bundled 默认数据源=CI services 对齐)
nohup java -Dlogback.configurationFile=/tmp/logback-quiet.xml --add-opens=java.base/java.nio=ALL-UNNAMED \
  -jar "$JAR" > /tmp/soak-mycat.log 2>&1 &
SOAK_PID=$!
trap 'kill -9 $SOAK_PID 2>/dev/null || true' EXIT

ready=0
for i in $(seq 1 60); do
  (exec 3<>/dev/tcp/127.0.0.1/8066) 2>/dev/null && { exec 3<&- 3>&-; ready=1; break; }
  sleep 1
done
[ "$ready" = 1 ] || { echo "RESULT=NO_DATA (8066 未就绪)"; exit 2; }
sleep 15

echo "=== G6 soak (30min) ==="
java -cp "$JAR" "$(dirname "$0")/G6SoakProbe.java"
RC=$?
kill -9 $SOAK_PID 2>/dev/null || true
[ "$RC" = 0 ] && { echo "RESULT=PASS"; exit 0; } || { echo "RESULT=RED"; exit 1; }
