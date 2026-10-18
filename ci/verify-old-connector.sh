#!/usr/bin/env bash
# I-DEBT-9 旧 Java Connector 线冒烟网（B 层仪器）：起 Mycat → 等 8066 →
# 用旧 Connector/J（5.1.x 旧类名 com.mysql.jdbc.Driver + 8.x com.mysql.cj.jdbc.Driver）
# 逐线跑 OldConnectorProbe（认证 + SELECT 1 + 版本 + 基本 CRUD 往返回读），全绿才放行。
#
# 为什么单独有这一个脚本（不复用 ci/verify-l1.sh）：verify-l1.sh 只喂**当前** connector 跑
# SELECT 1，量 L1 可达；旧线要量的是**旧客户端**过 Mycat 前端的兼容面（spec §3 M2"全 connector
# 跑基本 CRUD"）。多 Connector 版本不能同 classpath（类遮蔽），故每线一次 java -cp 单喂一个 jar。
#
# jar 坐标可用 OLD_CONNECTOR_COORDS 覆盖（空格分隔 groupId:artifactId:version），未给则用
# 缺省旧线清单；本地仓库路径由 mvn help:evaluate 推出，不写死绝对路径（与 verify-l1.sh 同姿势）。
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"

JAR="${OLDLINE_JAR:-server/target/server-1.22-release-jar-with-dependencies.jar}"
[ -f "$JAR" ] || { echo "找不到被测 jar：$JAR（先 mvn install -pl server -am）"; exit 1; }
JAR="$ROOT/$JAR"

MYCAT_HOST="${OLDLINE_MYCAT_HOST:-127.0.0.1}"
MYCAT_PORT="${OLDLINE_MYCAT_PORT:-8066}"
MYCAT_USER="${OLDLINE_MYCAT_USER:-root}"
MYCAT_PASSWORD="${OLDLINE_MYCAT_PASSWORD:-123456}"
MYCAT_SCHEMA="${OLDLINE_MYCAT_SCHEMA:-mysql}"
# 后端 8.0 用 MYSQL_8_0；旧线认证面与后端版本无关（Mycat 前端认证自成一格），persona 仅为对齐可用后端。
MYCAT_PERSONA="${OLDLINE_PERSONA:-MYSQL_8_0}"

# 缺省旧线清单：Connector/J 5.1 末版（早于 caching_sha2）+ 8.x 新 groupId 首档。
COORDS="${OLD_CONNECTOR_COORDS:-mysql:mysql-connector-java:5.1.49 com.mysql:mysql-connector-j:8.0.33}"

LOCAL_REPO="${OLDLINE_LOCAL_REPO:-$(mvn -q -B -DforceStdout help:evaluate -Dexpression=settings.localRepository)}"
# 跨平台归一：Windows 版 mvn 会吐 D:\... 反斜杠路径，bash 文件操作不认；正斜杠化。
# WSL 下若仓在 /mnt/d/... 用 OLDLINE_LOCAL_REPO 覆盖即可（与 verify-l1.sh 的 L1_CONNECTOR 覆盖同姿势）。
LOCAL_REPO="${LOCAL_REPO//\\//}"
echo "本地仓库 = $LOCAL_REPO"

# 从 maven 坐标解析 jar 路径；缺失则尝试离线解析（依赖已在本地仓时不联网）。
resolve_jar() { # <groupId:artifactId:version>
  local IFS=: g a v rest
  read -r g a v rest <<<"$1"
  local gp="${g//./\/}"
  echo "$LOCAL_REPO/$gp/$a/$v/$a-$v.jar"
}

LOG_DIR="target/ci"
mkdir -p "$LOG_DIR"
SERVER_LOG="$LOG_DIR/oldline-server.log"
: > "$SERVER_LOG"

wait_for_port() { # <host> <port> <秒数> <名称>
  local host=$1 port=$2 limit=$3 name=$4 i
  for i in $(seq 1 "$limit"); do
    if (exec 3<>"/dev/tcp/$host/$port") 2>/dev/null; then exec 3<&- 3>&-; echo "$name 就绪（第 ${i}s）"; return 0; fi
    sleep 1
  done
  echo "$name 在 ${limit}s 内未就绪"; return 1
}

# 端口预检：8066 被残留 Mycat 占用则拒跑（判定对象须是本轮起的 jar）。
port_busy=1
for i in $(seq 1 30); do
  if (exec 3<>"/dev/tcp/$MYCAT_HOST/$MYCAT_PORT") 2>/dev/null; then exec 3<&- 3>&-; sleep 1; else port_busy=0; break; fi
done
[ "$port_busy" = 0 ] || { echo "端口 $MYCAT_HOST:$MYCAT_PORT 等30s 仍被占用，先清掉残留 Mycat"; exit 1; }

# 起 Mycat
java "-Dmycat.persona=$MYCAT_PERSONA" --add-opens=java.base/java.nio=ALL-UNNAMED \
  -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8 -jar "$JAR" >"$SERVER_LOG" 2>&1 &
SERVER_PID=$!
cleanup() { kill "$SERVER_PID" 2>/dev/null || true; wait "$SERVER_PID" 2>/dev/null || true; }
trap cleanup EXIT
[ -d "/proc/$SERVER_PID" ] || { echo "Mycat 未能启动，日志尾部："; tail -n 40 "$SERVER_LOG"; exit 1; }
wait_for_port "$MYCAT_HOST" "$MYCAT_PORT" 60 "Mycat 前端 $MYCAT_PORT" || { echo "启动日志尾部："; tail -n 40 "$SERVER_LOG"; exit 1; }

# 逐旧线跑探针
overall=0
for coord in $COORDS; do
  jarpath="$(resolve_jar "$coord")"
  if [ ! -f "$jarpath" ]; then
    # 旧 jar 非工程依赖，本地仓可能没有：先试在线拉一次（CI 可网）；拉不到则诚实跳过。
    echo "本地仓缺 $coord，试 mvn dependency:get 拉取…"
    mvn -q -B dependency:get "-Dartifact=$coord" >/dev/null 2>&1 || true
    jarpath="$(resolve_jar "$coord")"
  fi
  if [ ! -f "$jarpath" ]; then
    echo "OLDLINE SKIP $coord  —— 本地仓缺 jar且在线拉取未成：$jarpath（宁挂账不伪绿）"
    overall=1
    continue
  fi
  echo "==== 旧线 $coord ($jarpath) ===="
  set +e
  OUT="$(java -Dfile.encoding=UTF-8 -cp "$jarpath" ci/OldConnectorProbe.java \
    "$MYCAT_HOST" "$MYCAT_PORT" "$MYCAT_USER" "$MYCAT_PASSWORD" "$MYCAT_SCHEMA" 2>&1)"
  RC=$?
  set -e
  echo "$OUT"
  if [ "$RC" -ne 0 ] || ! echo "$OUT" | grep -q "verdict=PASS"; then
    echo "OLDLINE FAIL $coord"
    overall=1
  else
    echo "OLDLINE PASS $coord"
  fi
done

# 服务端日志不允许出现影子断裂类错误（与 L1 同族守卫）
if grep -qE "NoSuchMethodError|NoClassDefFoundError" "$SERVER_LOG"; then
  echo "OLDLINE FAIL 服务端日志出现 NoSuchMethodError/NoClassDefFoundError："
  grep -E "NoSuchMethodError|NoClassDefFoundError" "$SERVER_LOG" | head -5
  overall=1
fi

exit "$overall"
