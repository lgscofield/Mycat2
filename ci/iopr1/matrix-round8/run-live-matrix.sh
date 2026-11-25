#!/usr/bin/env bash
# I-PR-1 Task 6 活体矩阵驱动。
#
# 两档配置各起一次活体（隔离 MYCAT_HOME，绝不改仓内生产 server.json）：
#   prod  = 生产 server.json 原值（maxPoolSize=1024，admitTimeoutMillis 缺省 200）⇒ L9/L2/L5/R2
#   small = maxPoolSize=3 + admitTimeoutMillis=50                                ⇒ L3/L8/L5/L6/L4/L10
# 两档都带 -Djdk.tracePinnedThreads=full，服务端日志即 L1 的决定性证据。
#
# 为什么用隔离 home 而不是改 resources 再重打 jar：ServerConfigurationImpl:31-38 只读
# <MYCAT_HOME>/server.json，classpath 那份是模板、boot 时根本不读 ⇒ 同一条配置通路、
# 同一个 jar，零污染生产资源，也省掉一次重打。
set -u
cd "D:/Workspaces/IdeaProjects/Mycat2" || exit 9

JDK="D:/Program Files/BellSoft/LibericaJDK-21-Full"
JAR="server/target/server-1.22-release-jar-with-dependencies.jar"
WS="ci/iopr1"
OUT="$WS/classes"
LOGS="$WS/logs"
HOMES="$WS/homes"
SRC_JSON="server/src/main/resources/server.json"
mkdir -p "$OUT" "$LOGS" "$HOMES"

[ -f "$JAR" ] || { echo "ABORT 找不到 $JAR（先 mvn -pl server -am package -DskipTests）"; exit 2; }

echo "=== backend readiness ==="
for c in m1-mysql-a m1-mysql-b; do
  docker exec "$c" mysqladmin ping -uroot -p123456 --silent >/dev/null 2>&1 \
    || { echo "ABORT backend $c not ready"; exit 2; }
  docker exec "$c" mysql -uroot -p123456 -e "SET GLOBAL max_connections=1000;" >/dev/null 2>&1 \
    || echo "WARN 未能抬升 $c max_connections"
done
echo "BACKEND_READY"

echo "=== compile LiveMatrixProbe ==="
"$JDK/bin/javac" -proc:none -encoding UTF-8 -cp "$JAR" -d "$OUT" "$WS/LiveMatrixProbe.java" 2>&1 | head -40
[ -f "$OUT/LiveMatrixProbe.class" ] || { echo "ABORT compile failed"; exit 2; }
echo "COMPILE_OK"

SUMMARY="$WS/summary.tsv"
printf "tag\tconfig\tverdict\tdetail\n" > "$SUMMARY"
MYCAT_PID=""

prepare_home() { # $1=tag  $2=maxPoolSize(空=保持原值)  $3=admitTimeoutMillis(空=不写)
  local home="$HOMES/$1"
  rm -rf "$home"; mkdir -p "$home/users" "$home/datasources" "$home/clusters"
  cp "$SRC_JSON" "$home/server.json"
  # 前端用户来自 <home>/users/*.json，不在 server.json 里。只拷 server.json 的话
  # boot 后用户表为空 ⇒ 每条连接 Access denied，resetConfig 也发不进去（先有鸡还是先有蛋）。
  cat > "$home/users/root.user.json" <<'USER_JSON'
{
  "username" : "root",
  "password" : "123456",
  "ip" : null,
  "transactionType" : "proxy",
  "dialect" : "mysql",
  "schema" : null,
  "isolation" : 3,
  "readOnly" : false,
  "loginLimit" : -1,
  "encryptType" : "",
  "encodeKey" : "",
  "role" : null,
  "admin" : true
}
USER_JSON
  # prototype 数据源必须在 boot 时就存在：MysqlVariableServiceImpl 构造器（hbt:29-54）
  # 只有在 jdbcConnectionManager 里查得到 prototype 时才去真库 SHOW SESSION VARIABLES；
  # 否则走兜底表，而兜底表把 max_allowed_packet 只塞进 globalVariables、漏了 sessionVariables
  # ⇒ Connector/J 握手期的 @@session.max_allowed_packet 取到 null ⇒ fixValue→toInt("null")
  # ⇒ NumberFormatException，连接直接建不起来。setup 里的 resetConfig 发生在 boot 之后，救不了。
  # 这是既有产品缺陷（该文件最后一次改动是 I-DEBT-69 ba4ccacfd，与本票无关），已挂账，不在本票修。
  cat > "$home/datasources/prototypeDs.datasource.json" <<'DS_JSON'
{
  "name" : "prototypeDs",
  "user" : "root",
  "password" : "123456",
  "maxCon" : 1000,
  "minCon" : 1,
  "maxRetryCount" : 5,
  "maxConnectTimeout" : 30000,
  "dbType" : "mysql",
  "url" : "jdbc:mysql://localhost:3306/mysql?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=UTF-8&autoReconnect=true",
  "weight" : 0,
  "initSqls" : [ ],
  "initSqlsGetConnection" : true,
  "instanceType" : "READ_WRITE",
  "idleTimeout" : 60000,
  "jdbcDriverClass" : null,
  "type" : "JDBC",
  "queryTimeout" : 0,
  "statementTimeout" : 30000,
  "removeAbandoned" : false,
  "removeAbandonedTimeoutSecond" : 180,
  "logAbandoned" : true
}
DS_JSON
  cat > "$home/clusters/prototype.cluster.json" <<'CLUSTER_JSON'
{
  "clusterType" : "MASTER_SLAVE",
  "switchType" : "SWITCH",
  "readBalanceType" : "BALANCE_ALL",
  "name" : "prototype",
  "readBalanceName" : null,
  "writeBalanceName" : null,
  "masters" : [ "prototypeDs" ],
  "replicas" : [ ],
  "heartbeat" : {
    "maxRetryCount" : 3,
    "minSwitchTimeInterval" : 300,
    "heartbeatTimeout" : 1000,
    "slaveThreshold" : 0.0,
    "showLog" : false
  },
  "maxCon" : 200,
  "timer" : null
}
CLUSTER_JSON
  if [ -n "$2" ]; then
    node -e '
      const fs=require("fs");
      const [file,max,admit]=process.argv.slice(1);
      const j=JSON.parse(fs.readFileSync(file,"utf8"));
      j.server=j.server||{};
      j.server.workerPool=j.server.workerPool||{};
      j.server.workerPool.maxPoolSize=parseInt(max,10);
      if(admit!=="") j.server.workerPool.admitTimeoutMillis=parseInt(admit,10);
      fs.writeFileSync(file,JSON.stringify(j,null,2));
      console.log("HOME_CONFIG maxPoolSize="+j.server.workerPool.maxPoolSize+" admitTimeoutMillis="+j.server.workerPool.admitTimeoutMillis);
    ' "$home/server.json" "$2" "$3"
  else
    echo "HOME_CONFIG production-values-unchanged"
  fi
}

start_mycat() { # $1=tag
  local home="$HOMES/$1"
  local log="$LOGS/$1-server.log"
  # 端口预检：8066 若被残留/外来进程持有，"等就绪 + 跑探针"会打到我不控制的进程上，
  # 得出的红绿与被测 jar 无关 ⇒ 那种判定不算数，直接拒跑（同 ci/verify-l1.sh:57-73）。
  # 上一档刚 stop，端口可能晚几秒释放，故先等一段而不是一发现占用就错。
  local busy=1
  for i in $(seq 1 30); do
    if (exec 3<>"/dev/tcp/127.0.0.1/8066") 2>/dev/null; then
      exec 3<&- 3>&- 2>/dev/null || true; sleep 1
    else
      busy=0; break
    fi
  done
  if [ "$busy" = 1 ]; then
    echo "ABORT 端口 8066 等30s 仍被占用 tag=$1，先清残留 Mycat 再跑（否则判定对象不明）"; exit 3
  fi
  "$JDK/bin/java" -Djdk.tracePinnedThreads=full -DMYCAT_HOME="$home" \
    --add-opens=java.base/java.nio=ALL-UNNAMED \
    -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8 \
    -jar "$JAR" > "$log" 2>&1 &
  MYCAT_PID=$!
  local ready=0
  for i in $(seq 1 90); do
    if (exec 3<>"/dev/tcp/127.0.0.1/8066") 2>/dev/null; then
      exec 3<&- 3>&- 2>/dev/null || true; ready=1; break
    fi
    if ! kill -0 "$MYCAT_PID" 2>/dev/null; then echo "ABORT mycat 进程已退出 tag=$1"; tail -30 "$log"; exit 3; fi
    sleep 1
  done
  [ "$ready" = 1 ] || { echo "ABORT 8066 未就绪 tag=$1"; tail -30 "$log"; exit 3; }
  sleep 15
  echo "MYCAT_UP tag=$1 pid=$MYCAT_PID"
}

stop_mycat() {
  [ -n "$MYCAT_PID" ] || return 0
  kill "$MYCAT_PID" 2>/dev/null || true
  for i in $(seq 1 20); do kill -0 "$MYCAT_PID" 2>/dev/null || break; sleep 1; done
  kill -9 "$MYCAT_PID" 2>/dev/null || true
  wait "$MYCAT_PID" 2>/dev/null || true
  MYCAT_PID=""
}
trap 'stop_mycat' EXIT

run() { # $1=tag $2=config $3..=probe args
  local tag="$1"; local cfg="$2"; shift 2
  local log="$LOGS/${tag}.log"
  echo "--- RUN $tag config=$cfg args=$* ---"
  "$JDK/bin/java" -Dfile.encoding=UTF-8 -cp "$JAR;$OUT" LiveMatrixProbe "$@" > "$log" 2>&1
  local rc=$?
  local line
  line=$(grep -a -m1 '^RESULT ' "$log" || true)
  if [ -z "$line" ]; then
    echo "NO_RESULT rc=$rc tag=$tag"; tail -15 "$log"
    printf "%s\t%s\tNO_RESULT\trc=%s\n" "$tag" "$cfg" "$rc" >> "$SUMMARY"
    return
  fi
  local verdict
  verdict=$(echo "$line" | tr ' ' '\n' | grep -a -m1 '^verdict=' | cut -d= -f2-)
  echo "$line"
  printf "%s\t%s\t%s\t%s\n" "$tag" "$cfg" "$verdict" "$line" >> "$SUMMARY"
}

# ---------------------------------------------------------------- 档一：生产原值
prepare_home prod "" ""
start_mycat prod
run prod-setup   prod setup
run prod-load    prod load  127.0.0.1 8066 32 20
run prod-threads prod threads 127.0.0.1 8066 8 32 8
run prod-seq     prod seq   127.0.0.1 8066 8 60 1024
run prod-ddl     prod ddl   127.0.0.1 8066 8 10
run prod-r4      prod http-rule 127.0.0.1 8066
stop_mycat

# ---------------------------------------------------------------- 档二：小许可 + 短准入等待
prepare_home small 3 50
start_mycat small
run small-setup    small setup
run small-saturate small saturate 127.0.0.1 8066 3 6
run small-ops      small ops      127.0.0.1 8066 3 10
run small-seq-fit  small seq      127.0.0.1 8066 3 60 3
run small-seq-sat  small seq      127.0.0.1 8066 9 60 3
run small-deadline small deadline 127.0.0.1 8066 3
run small-abandon  small abandon  127.0.0.1 8066 3 6
run small-r5       small sql-log   127.0.0.1 8066
stop_mycat

# ---------------------------------------------------------------- L1 / L7 汇总
# 「零 pinning」只有在真跑过并发负载时才有意义。首轮 12 个场景全因 Access denied 落空，
# 日志里当然 0 帧——那种 PASS 是空断言。故 L1/L7 一律先过流量闸：无负载 ⇒ NO_DATA，不得标绿。
TRAFFIC=$(awk -F'\t' '($1=="prod-load"||$1=="prod-threads"||$1=="small-saturate") && $3=="PASS"{n++} END{print n+0}' "$SUMMARY")
echo "L1_L7_TRAFFIC_SCENARIOS_PASSED=$TRAFFIC"

echo "=== L1 pinning ==="
PIN_TOTAL=0
for f in "$LOGS"/prod-server.log "$LOGS"/small-server.log; do
  n=$(grep -c '<== monitors' "$f" 2>/dev/null || true)
  echo "PINNED_FRAMES $(basename "$f") = $n"
  PIN_TOTAL=$((PIN_TOTAL + n))
done
echo "L1_PINNED_TOTAL=$PIN_TOTAL"
# 按监视器持有者分类。ObservableRefCount.subscribeActual 是 RxJava 自己的 synchronized，
# 由 AsyncMycatDataContextImpl:383 的 .share() 引入；换掉我方锁也消不掉这层第三方监视器，
# 去掉 share() 会让复用节点的后端查询按消费者重复执行 = 正确性/负载回归 ⇒ 架构级，已挂账另立票。
# 豁免帧**单列且不得计入 PASS**：本票 L1 出口是「我方零钉住」，残差如实报 RESIDUAL，不伪绿。
PIN_EXEMPT=$(grep -h '<== monitors' "$LOGS"/prod-server.log "$LOGS"/small-server.log 2>/dev/null | grep -c 'ObservableRefCount.subscribeActual' || true)
PIN_OURS=$((PIN_TOTAL - PIN_EXEMPT))
echo "L1_PINNED_OURS=$PIN_OURS"
echo "L1_PINNED_EXEMPT_RXJAVA=$PIN_EXEMPT"
if [ "$PIN_OURS" != 0 ]; then L1_VERDICT=FAIL
elif [ "$TRAFFIC" = 0 ]; then L1_VERDICT=NO_DATA
elif [ "$PIN_EXEMPT" != 0 ]; then L1_VERDICT=RESIDUAL
else L1_VERDICT=PASS; fi
printf "L1-pinning\tboth\t%s\tpinnedFrames=%s ours=%s exemptRxJava=%s trafficScenarios=%s\n" "$L1_VERDICT" "$PIN_TOTAL" "$PIN_OURS" "$PIN_EXEMPT" "$TRAFFIC" >> "$SUMMARY"

echo "=== pinning hotspots ==="
grep -h '<== monitors' "$LOGS"/*-server.log 2>/dev/null | sed 's/^[[:space:]]*//' | sort | uniq -c | sort -rn | head -25

echo "=== L7 heartbeat / backend-dead markers ==="
HB=$(grep -hic "heartbeat.*error\|heartbeat.*fail\|switch.*datasource\|backend.*dead" "$LOGS"/*-server.log 2>/dev/null | paste -sd+ | bc 2>/dev/null || echo 0)
echo "L7_HEARTBEAT_ERROR_LINES=$HB"
if [ "$HB" != 0 ]; then L7_VERDICT=FAIL
elif [ "$TRAFFIC" = 0 ]; then L7_VERDICT=NO_DATA
else L7_VERDICT=PASS; fi
printf "L7-heartbeat\tboth\t%s\theartbeatErrorLines=%s trafficScenarios=%s\n" "$L7_VERDICT" "$HB" "$TRAFFIC" >> "$SUMMARY"

echo "=== ignored-knob WARN (名实相符) ==="
grep -ha "I-PR-1: workerPool\|I-PR-1: timeWorkerPool" "$LOGS"/small-server.log 2>/dev/null | head -6

echo "=== SUMMARY ==="
cat "$SUMMARY"
echo "LIVE_MATRIX_DONE"