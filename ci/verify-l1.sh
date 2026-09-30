#!/usr/bin/env bash
# M2-1 L1 端到端门：起 Mycat → 等 8066 就绪 → 真客户端跑一条查询 → 断服务端日志无 NoSuchMethodError。
#
# 为什么单独有这一个脚本（不复用 ci/run-example.sh）：D1 那类缺陷（druid 影子类导致的
# NoSuchMethodError）只在连接期服务端构造 visitor 时才炸，编译和单测都量不到；
# 而 example 模块自身的测试当时是坏的（D13），拿它当门等于没有门。
#
# jar 可用 L1_JAR 覆盖（默认取构建产物）；connector 可用 L1_CONNECTOR 覆盖，
# 未给则从根 pom 的 mysql.connector.version + maven 本地仓库推出，不写死绝对路径。
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"

JAR="${L1_JAR:-server/target/server-1.22-release-jar-with-dependencies.jar}"
[ -f "$JAR" ] || { echo "找不到被测 jar：$JAR（先 mvn install -pl server -am）"; exit 1; }
JAR="$ROOT/$JAR"

MYCAT_HOST="${L1_MYCAT_HOST:-127.0.0.1}"
MYCAT_PORT="${L1_MYCAT_PORT:-8066}"
MYCAT_USER="${L1_MYCAT_USER:-root}"
MYCAT_PASSWORD="${L1_MYCAT_PASSWORD:-123456}"
MYCAT_SCHEMA="${L1_MYCAT_SCHEMA:-mysql}"

LOG_DIR="target/ci"
mkdir -p "$LOG_DIR"
SERVER_LOG="$LOG_DIR/l1-server.log"
: > "$SERVER_LOG"

wait_for_port() { # <host> <port> <秒数> <名称>
  local host=$1 port=$2 limit=$3 name=$4 i
  for i in $(seq 1 "$limit"); do
    if (exec 3<>"/dev/tcp/$host/$port") 2>/dev/null; then
      exec 3<&- 3>&-
      echo "$name 就绪（第 ${i}s）"
      return 0
    fi
    sleep 1
  done
  echo "$name 在 ${limit}s 内未就绪"
  return 1
}

# 1) connector：客户端侧依赖，与服务端 jar 分离，避免探针被被测物污染
# 注：本机若在 WSL 里跑，PATH 上的 mvn 是 Windows 版，help:evaluate 会输出 D:\... 这种
# bash 用不了的路径；那时直接给 L1_CONNECTOR 指一个 /mnt/d/... 下的 jar 即可（CI 里不需覆盖）。
if [ -z "${L1_CONNECTOR:-}" ]; then
  CONNECTOR_VERSION="$(sed -n 's:.*<mysql.connector.version>\(.*\)</mysql.connector.version>.*:\1:p' pom.xml | head -1)"
  [ -n "$CONNECTOR_VERSION" ] || { echo "根 pom 里取不到 mysql.connector.version"; exit 1; }
  LOCAL_REPO="$(mvn -q -B -DforceStdout help:evaluate -Dexpression=settings.localRepository)"
  L1_CONNECTOR="$LOCAL_REPO/com/mysql/mysql-connector-j/$CONNECTOR_VERSION/mysql-connector-j-$CONNECTOR_VERSION.jar"
fi
[ -f "$L1_CONNECTOR" ] || { echo "connector 不存在：$L1_CONNECTOR"; exit 1; }
echo "connector = $L1_CONNECTOR"

# 2) 起 Mycat
# 端口预检：若 8066 已被占用（上一轮残留或被其他进程持有），本脚本“等就绪 + 跑探针”会打到
# 一个我不控制的进程上，得出的红/绿与被测 jar 无关 —— 那种绿灯不算数，直接拒跑。
# 前一步刚跑过 Mycat 的 CI 里端口会晚几秒才释放，所以先给一段等待而不是一发现占用就错。
port_busy=1
for i in $(seq 1 30); do
  if (exec 3<>"/dev/tcp/$MYCAT_HOST/$MYCAT_PORT") 2>/dev/null; then
    exec 3<&- 3>&-
    sleep 1
  else
    port_busy=0
    break
  fi
done
if [ "$port_busy" = 1 ]; then
  echo "端口 $MYCAT_HOST:$MYCAT_PORT 等30s 仍被占用，先清掉残留的 Mycat 再跑（否则判定对象不明）"
  exit 1
fi
java -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8 -jar "$JAR" >"$SERVER_LOG" 2>&1 &
SERVER_PID=$!
cleanup() {
  kill "$SERVER_PID" 2>/dev/null || true
  wait "$SERVER_PID" 2>/dev/null || true
}
trap cleanup EXIT

# 3) 轮询就绪（不用裸 sleep：M1 已证明固定 sleep 在 CI 上不稳）
if [ ! -d "/proc/$SERVER_PID" ]; then
  echo "Mycat 进程未能启动，日志尾部："
  tail -n 40 "$SERVER_LOG"
  exit 1
fi
wait_for_port "$MYCAT_HOST" "$MYCAT_PORT" 60 "Mycat 前端 $MYCAT_PORT" || {
  echo "启动日志尾部："
  tail -n 40 "$SERVER_LOG"
  exit 1
}

# 4) 真客户端跑一条查询
set +e
PROBE_OUT="$(java -cp "$L1_CONNECTOR" ci/L1Probe.java "$MYCAT_HOST" "$MYCAT_PORT" \
  "$MYCAT_USER" "$MYCAT_PASSWORD" "$MYCAT_SCHEMA" 2>&1)"
PROBE_RC=$?
set -e
echo "$PROBE_OUT"

# 5) 服务端日志里不允许出现影子断裂那类错误
if grep -qE "NoSuchMethodError|NoClassDefFoundError" "$SERVER_LOG"; then
  echo "L1 FAIL 服务端日志出现 NoSuchMethodError/NoClassDefFoundError："
  grep -E "NoSuchMethodError|NoClassDefFoundError" "$SERVER_LOG" | head -5
  exit 1
fi

if [ "$PROBE_RC" -ne 0 ] || ! echo "$PROBE_OUT" | grep -q "^L1 OK rows="; then
  echo "L1 FAIL 探针未拿到结果集（退出码 $PROBE_RC），日志尾部："
  tail -n 40 "$SERVER_LOG"
  exit 1
fi

grep -q "started up" "$SERVER_LOG" && echo "服务端启动日志已确认 started up"
echo "$PROBE_OUT"
