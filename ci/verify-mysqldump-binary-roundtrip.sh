#!/usr/bin/env bash
# I-DEBT-54 真二进制 mysqldump/mariadb-dump 全类型往返回灌 · 逐矩阵档金标准网。
#
# 与既有 JDBC 模拟（DumpRestoreRoundTripMatrixTest，M4）不同，本网量的是**真客户端二进制**
# （跑在与矩阵同版的容器里，主机零装 mysqldump，版本天然对齐）从 Mycat 前端 dump 结构+数据 →
# 回灌原生靶库 → 逐值保真。覆盖含 bit(8) 的全类型链（decimal/datetime NULL/STORED 生列/中文 text）。
#
# 为什么单独有这一个脚本（不复用 ci/run-example.sh 的 JDBC 面）：真二进制走的是 mysqldump 自己的
# 元数据查询链（tablespaces / column-statistics / SHOW ...），与 JDBC 结果集表征是不同的破面口径；
# D1/D2/D3 当年正是从这里暴露。金标准判据 = dump RC=0 + 回灌 RC=0 + 逐值命中。
#
# 关键防坑（务必字节安全回灌）：bit(8) 列 mysqldump 会输出 `_binary '<raw byte>'`（如 0xAA）。
# 回灌只能用 bash `... < dump.sql` 直接重定向；若经宿主文本管道（Get-Content/字符集重编码）会把
# 非 ASCII 字节改写（实测 0xAA→0xFF）造成**假红**。读回也用 ASCII 安全表达（BIN/HEX）断言。
#
# 已知非致命残留：MySQL 档 mysqldump 会向 stderr 打一条 tablespaces 的 Calcite "EXTRA not grouped"
# 消息但 **RC 仍=0**（见 d1d2 取证 D2b：Mycat 未随 session sql_mode 放宽 ONLY_FULL_GROUP_BY）。
# 本网只按 RC + 数据保真判定，**不因该 stderr 消息误红**；MariaDB 档通常不产生该消息。
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"

JAR="${BINRT_JAR:-server/target/server-1.22-release-jar-with-dependencies.jar}"
[ -f "$JAR" ] || { echo "找不到被测 jar：$JAR（先 mvn install -pl server -am）"; exit 1; }
JAR="$ROOT/$JAR"

# 矩阵镜像决定 flavor（认证 flag 方言）；客户端/dump 二进制名在镜像内运行期探测，跨版本自适应。
COMPAT_DB="${COMPAT_DB:-mysql:8.0}"
MYCAT_PERSONA="${MYCAT_PERSONA:-MYSQL_8_0}"
MYCAT_USER="${BINRT_USER:-root}"
MYCAT_PASSWORD="${BINRT_PASSWORD:-123456}"

# 前端 Mycat：宿主视角 127.0.0.1:8066。
MYCAT_HOST="${BINRT_MYCAT_HOST:-127.0.0.1}"
MYCAT_PORT="${BINRT_MYCAT_PORT:-8066}"
# 容器→宿主可达方式随平台（根因，2026-10-19 诊断推翻旧「runner flaky」定性）：
# Mycat 运行期只绑回环（fat-jar 走代码缺省 ServerConfig.ip="localhost"，server.json 的 0.0.0.0 未被加载），
# 故 GitHub Linux runner 上容器经 --add-host=host.docker.internal:host-gateway 连 8066 必 ECONNREFUSED(111)（确定性、非抖动）。
# Linux 改用 --network host 进宿主网络命名空间直连 127.0.0.1（回环 Mycat + 已发布 0.0.0.0:3307 后端均可达）；
# 非 Linux（本地 Windows/Docker Desktop）沿用 host-gateway 路径不破（本地 host.docker.internal 代理到宿主回环，实测 PASS）。
HOST_OS="$(uname -s 2>/dev/null || echo unknown)"
if [ "$HOST_OS" = "Linux" ]; then
  NETARG=(--network host)
  CONTAINER_HOST="${BINRT_CONTAINER_HOST:-127.0.0.1}"
else
  NETARG=(--add-host=host.docker.internal:host-gateway)
  CONTAINER_HOST="${BINRT_CONTAINER_HOST:-host.docker.internal}"
fi
# 回灌靶库：mysql-b/mariadb-b 服务发布的 3307（原生后端，绕开 Mycat 直灌）。
BACKEND_PORT="${BINRT_BACKEND_PORT:-3307}"

command -v docker >/dev/null 2>&1 || { echo "需要 docker（容器化真二进制，主机零装 mysqldump）"; exit 1; }

# ---- flavor：认证 flag 方言（MariaDB 客户端不认 --ssl-mode/--get-server-public-key）----
case "$COMPAT_DB" in
  mariadb:*)
    FLAVOR=mariadb
    AUTH=(--ssl=0)
    ;;
  *)
    FLAVOR=mysql
    # caching_sha2 非安全连接需检索公钥；后端 8.0/8.4/9.0 与 Mycat MYSQL_8_x 画像同口径。
    AUTH=(--get-server-public-key --ssl-mode=DISABLED)
    ;;
esac
CHARSET=(--default-character-set=utf8mb4)

# ---- 运行期探测镜像内的二进制名（mariadb 10.6 与 11.x 命名不同，MySQL 只有 mysqldump）----
if docker run --rm "$COMPAT_DB" sh -c 'command -v mariadb-dump' >/dev/null 2>&1; then
  DUMP_BIN=mariadb-dump; CLIENT_BIN=mariadb
else
  DUMP_BIN=mysqldump; CLIENT_BIN=mysql
fi
echo "flavor=$FLAVOR image=$COMPAT_DB persona=$MYCAT_PERSONA client=$CLIENT_BIN dump=$DUMP_BIN host_os=$HOST_OS container_host=$CONTAINER_HOST net=${NETARG[*]}"

LOG_DIR="target/ci"
mkdir -p "$LOG_DIR"
SERVER_LOG="$LOG_DIR/binroundtrip-server.log"
SEED_SQL="$LOG_DIR/binroundtrip-seed.sql"
DUMP_SQL="$LOG_DIR/binroundtrip-dump.sql"
DUMP_ERR="$LOG_DIR/binroundtrip-dump.err"
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

# ---- 起 Mycat（persona 随矩阵档，认证/画像对齐后端）----
java "-Dmycat.persona=$MYCAT_PERSONA" --add-opens=java.base/java.nio=ALL-UNNAMED \
  -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8 -jar "$JAR" >"$SERVER_LOG" 2>&1 &
SERVER_PID=$!
cleanup() { kill "$SERVER_PID" 2>/dev/null || true; wait "$SERVER_PID" 2>/dev/null || true; }
trap cleanup EXIT
[ -d "/proc/$SERVER_PID" ] || { echo "Mycat 未能启动，日志尾部："; tail -n 40 "$SERVER_LOG"; exit 1; }
wait_for_port "$MYCAT_HOST" "$MYCAT_PORT" 60 "Mycat 前端 $MYCAT_PORT" || { echo "启动日志尾部："; tail -n 40 "$SERVER_LOG"; exit 1; }

# ---- 播种脚本（全类型含 bit(8) + 中文；写成 UTF-8 文件，走字节安全 < 喂入）----
# CREATE DATABASE 显式 utf8mb4（非绕过，是合法输入声明；2026-10-19 取证根因 (1)）：
#  mysql:5.7 服务端默认字符集是 latin1（my.cnf 未抬），裸建库会让列继承 latin1
#  → 中文 INSERT 报 ERROR 1366（run@61eb56afa 三档全红=确定性）；8.x/MariaDB 各档显式声明为无操作，零副作用。
# ✅ 2026-10-29 撤除表级绕过（I-DEBT-58 收口复验）：曾根因 (2)=Mycat SHOW CREATE DATABASE 仿真面丢库级
#  字符集，dump 出裸 `CREATE DATABASE dbdump;`，回灌 latin1 默认靶中文落 3F3F（run@a4c398734），当时
#  `ed7795914` 以 CREATE TABLE 钉表级 utf8mb4 绕过。Design A 修复（`a38e6fa6f` 透传读回后端真实库定义）
#  已随 CI 全档绿；本 seed 的 CREATE TABLE 已去掉表级 CHARACTER SET/COLLATE，dump 须靠库级子句保真
#  ——即本步同时是 I-DEBT-58 的端到端复验网（撤绕过判据：5.7 档 STEP4 HEX(name)=E68EA2E99288 非 3F3F）。
#  本地复演背书（2026-10-29，persona=MYSQL_5_7 + my57-3306 源 + 官方镜像默认 latin1 靶 tmp57lat:3317）：
#  SHOW CREATE DATABASE 返 `CREATE DATABASE \`dbdump\` /*!40100 DEFAULT CHARACTER SET utf8mb4 */`（与真 5.7 逐字一致）、
#  mysqldump 默认口径 rc=0、回灌 latin1 靶 rc=0、逐值全等（含中文 HEX/bit(8)/生列/NULL）。
cat > "$SEED_SQL" <<'SQL'
DROP DATABASE IF EXISTS dbdump;
CREATE DATABASE dbdump CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE dbdump;
CREATE TABLE t1 (id INT PRIMARY KEY, name VARCHAR(64), amount DECIMAL(10,2), ts DATETIME NULL, gen INT AS (id*2) STORED, b BIT(8), txt TEXT);
INSERT INTO t1 (id,name,amount,ts,b,txt) VALUES (1,'探针',10.50,'2026-01-01 10:00:00',b'10101010','hello'),(2,'abc',20.75,NULL,b'00001111','plain');
SQL

# 回灌靶/主源 best-effort 清库（原生直连，忽略不可达/不存在）
docker run -i --rm "${NETARG[@]}" "$COMPAT_DB" "$CLIENT_BIN" "${AUTH[@]}" \
  -h "$CONTAINER_HOST" -P "$BACKEND_PORT" -u"$MYCAT_USER" -p"$MYCAT_PASSWORD" \
  -e 'DROP DATABASE IF EXISTS dbdump;' >/dev/null 2>&1 || true

# ---- STEP 1 正向：经 Mycat 8066 播种源库（容器真客户端走真协议）----
echo "== STEP 1: seed via Mycat 8066 =="
if ! docker run -i --rm "${NETARG[@]}" "$COMPAT_DB" "$CLIENT_BIN" "${AUTH[@]}" "${CHARSET[@]}" \
     -h "$CONTAINER_HOST" -P "$MYCAT_PORT" -u"$MYCAT_USER" -p"$MYCAT_PASSWORD" < "$SEED_SQL"; then
  echo "BINRT FAIL 播种失败（seed rc!=0）"; exit 1
fi

# ---- STEP 2 正向：容器化真二进制 mysqldump/mariadb-dump 默认口径 dump Mycat ----
echo "== STEP 2: dump via $DUMP_BIN from Mycat 8066 =="
set +e
docker run --rm "${NETARG[@]}" "$COMPAT_DB" "$DUMP_BIN" "${AUTH[@]}" "${CHARSET[@]}" \
  -h "$CONTAINER_HOST" -P "$MYCAT_PORT" -u"$MYCAT_USER" -p"$MYCAT_PASSWORD" \
  --databases dbdump 1>"$DUMP_SQL" 2>"$DUMP_ERR"
DUMP_RC=$?
set -e
if [ "$DUMP_RC" -ne 0 ]; then
  echo "BINRT FAIL dump rc=$DUMP_RC（致命），dump.err："; cat "$DUMP_ERR"; exit 1
fi
echo "dump rc=0（允许下述非致命 stderr）："; cat "$DUMP_ERR" 2>/dev/null || true
[ -s "$DUMP_SQL" ] || { echo "BINRT FAIL dump.sql 为空"; exit 1; }
grep -q 'INSERT INTO' "$DUMP_SQL" || { echo "BINRT FAIL dump.sql 无 INSERT（数据面缺失）"; exit 1; }

# ---- STEP 3 反向：字节安全回灌原生靶库 3307（bash < file，禁宿主文本管道）----
echo "== STEP 3: restore into native backend :$BACKEND_PORT (byte-safe) =="
set +e
docker run -i --rm "${NETARG[@]}" "$COMPAT_DB" "$CLIENT_BIN" "${AUTH[@]}" "${CHARSET[@]}" \
  -h "$CONTAINER_HOST" -P "$BACKEND_PORT" -u"$MYCAT_USER" -p"$MYCAT_PASSWORD" < "$DUMP_SQL"
RESTORE_RC=$?
set -e
[ "$RESTORE_RC" -eq 0 ] || { echo "BINRT FAIL restore rc=$RESTORE_RC（回灌报错，如 bit/_binary 1406 类）"; exit 1; }

# ---- STEP 4 逐值读回（ASCII 安全：BIN(bit)/HEX(中文)/decimal/ISNULL/生列/text）----
echo "== STEP 4: readback + 逐值断言 =="
set +e
RB="$(docker run --rm "${NETARG[@]}" "$COMPAT_DB" "$CLIENT_BIN" "${AUTH[@]}" "${CHARSET[@]}" \
  -h "$CONTAINER_HOST" -P "$BACKEND_PORT" -u"$MYCAT_USER" -p"$MYCAT_PASSWORD" \
  -N -B -e 'SELECT id,HEX(name),BIN(b),amount,ISNULL(ts),gen,txt FROM dbdump.t1 ORDER BY id;' 2>&1)"
RB_RC=$?
set -e
echo "$RB"
[ "$RB_RC" -eq 0 ] || { echo "BINRT FAIL 读回查询 rc=$RB_RC"; exit 1; }

# 地面真相（本地 mysql:8.0 实测锁定，非猜测）：
#   row1 探针→HEX=E68EA2E99288, BIN(b)=10101010(0xAA), amount=10.50, tsnull=0, gen=2, txt=hello
#   row2 abc →HEX=616263,          BIN(b)=1111(0x0F),   amount=20.75, tsnull=1, gen=4, txt=plain
# 断言全部命中标记；'1111' 只可能来自 row2（'10101010' 不含连续 1111），无歧义。
overall=0
for tok in E68EA2E99288 10101010 616263 1111 10.50 20.75 hello plain; do
  if ! printf '%s\n' "$RB" | grep -q -- "$tok"; then
    echo "BINRT FAIL 逐值缺失：$tok"; overall=1
  fi
done

# 影子断裂族守卫（与 L1/旧线同族）
if grep -qE "NoSuchMethodError|NoClassDefFoundError" "$SERVER_LOG"; then
  echo "BINRT FAIL 服务端日志出现 NoSuchMethodError/NoClassDefFoundError："
  grep -E "NoSuchMethodError|NoClassDefFoundError" "$SERVER_LOG" | head -5
  overall=1
fi

if [ "$overall" -ne 0 ]; then exit 1; fi
echo "BINRT PASS $COMPAT_DB ($DUMP_BIN) 全类型含 bit(8) 真二进制往返回灌逐值保真"
