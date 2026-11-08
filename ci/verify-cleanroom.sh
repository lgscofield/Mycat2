#!/usr/bin/env bash
# I-DEBT-86 净室启动护栏：mycat-home 仅 datasources+users（无 clusters/、无 schemas/、无 state）——
# 修复前该形态下任何 JDBC 客户端连 8066 即死（Connector/J 引导查询 NumberFormatException: "null"，
# 根因链见 DEBT-QUEUE I-DEBT-86 / docs/superpowers/specs/2026-11-08-i-debt-86-*.md）。
# 本脚本是 CI/本地可重跑的回归网：临时净室 home → 起 jar（独立端口，缺省 8067）→
# 纯 TCP 就绪 → CleanroomProbe 断言连通 + 引导 22 变量全可读 → 杀进程。
# 出口码三元语义（对齐 run-example-guard.ps1 刀③）：0=PASS / 1=RED / 2=NO_DATA。
set -uo pipefail

JAR="server/target/server-1.22-release-jar-with-dependencies.jar"
[ -f "$JAR" ] || { echo "RESULT=NO_DATA (找不到构建产物 $JAR，先 mvn package)"; exit 2; }
command -v java >/dev/null 2>&1 || { echo "RESULT=NO_DATA (无 java)"; exit 2; }

PORT="${CLEANROOM_PORT:-8067}"
# 后端凭据可覆盖（CI docker= root/123456；本机可 CLEANROOM_PROTO_USER=root CLEANROOM_PROTO_PASSWORD=root）
PROTO_USER="${CLEANROOM_PROTO_USER:-root}"
PROTO_PASSWORD="${CLEANROOM_PROTO_PASSWORD:-123456}"
WORK="$(mktemp -d)"
trap 'kill $MYCAT_PID 2>/dev/null || true; rm -rf "$WORK"' EXIT

mkdir -p "$WORK/datasources" "$WORK/users"
cat > "$WORK/server.json" <<EOF
{"loadBalance":{"defaultLoadBalance":"BalanceRandom","loadBalances":[]},"mode":"local","properties":{},
 "server":{"ip":"0.0.0.0","mycatId":86,"port":$PORT,"reactorNumber":2,
 "workerPool":{"corePoolSize":1,"keepAliveTime":1,"maxPendingLimit":65535,"maxPoolSize":32,"taskTimeout":5,"timeUnit":"MINUTES"}}}
EOF
cat > "$WORK/users/root.user.json" <<'EOF'
{"dialect":"mysql","ip":null,"password":"123456","transactionType":"proxy","username":"root"}
EOF
cat > "$WORK/datasources/prototypeDs.datasource.json" <<EOF
{"dbType":"mysql","idleTimeout":60000,"initSqls":[],"initSqlsGetConnection":true,"instanceType":"READ_WRITE",
 "maxCon":50,"maxConnectTimeout":30000,"maxRetryCount":5,"minCon":1,"name":"prototypeDs","password":"$PROTO_PASSWORD","type":"JDBC",
 "url":"jdbc:mysql://127.0.0.1:3306/mysql?useUnicode=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true",
 "user":"$PROTO_USER","weight":0}
EOF

echo "== 净室 home（无 clusters/）: $WORK =="
java --add-opens=java.base/java.nio=ALL-UNNAMED -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8 \
  "-DMYCAT_HOME=$WORK" -jar "$JAR" > "$WORK/boot.log" 2>&1 &
MYCAT_PID=$!

ready=0
for i in $(seq 1 45); do
  if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then exec 3<&- 3>&- 2>/dev/null || true; ready=1; break; fi
  sleep 1
done
if [ "$ready" != 1 ]; then
  echo "RESULT=NO_DATA (净室实例 $PORT s 内未就绪)"; tail -5 "$WORK/boot.log"; exit 2
fi
sleep 8   # settle：端口可连≠配置全部就绪（run-example.sh 同口径）

echo "== CleanroomProbe (port=$PORT) =="
java -cp "$JAR" "$(dirname "$0")/CleanroomProbe.java" "$PORT"
PROBE_RC=$?
case $PROBE_RC in
  0) echo "RESULT=PASS"; exit 0 ;;
  2) echo "RESULT=NO_DATA"; exit 2 ;;
  *) echo "RESULT=RED"; exit 1 ;;
esac
