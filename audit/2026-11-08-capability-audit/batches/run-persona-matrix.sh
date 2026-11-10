#!/usr/bin/env bash
# I-DEBT-96 轮2: 七版本 persona 矩阵 —— 每版本: 独立实例(8069, -Dmycat.persona)→ compat 四件套 → 记录 → 杀
# 前置: WSL 七后端 3401-3407 已就绪(MATRIX96-ENV);本地批次跑完(无 maven 并发)
# 注意: 独占 8069 + 独占 maven(勿与其它 mvn 并行);每版本超时 40 分钟
set -u
A="D:/Workspaces/Lingma/mycat2/audit/2026-11-08-capability-audit"
JDK="D:/Program Files/BellSoft/LibericaJDK-21-Full"
MY="$JDK/bin/java"
R="$A/persona-matrix"
mkdir -p "$R"
export JAVA_HOME="$JDK"; export PATH="$JDK/bin:$PATH"

# 版本→端口→persona 三元表(WSL 3401-3407)
declare -a SPECS=(
  "3401 mysql5.7  MYSQL_5_7"
  "3402 mysql8.0  MYSQL_8_0"
  "3403 mysql8.4  MYSQL_8_4"
  "3404 mysql9.6  MYSQL_9_X"
  "3405 mariadb10.6  MARIADB_10_6"
  "3406 mariadb10.11 MARIADB_10_11"
  "3407 mariadb11.8  MARIADB_11_4"
)

for spec in "${SPECS[@]}"; do
  IFS=' ' read -r tag port persona <<< "$spec"
  HOME_DIR="$A/pm-$persona"
  echo "=== [$tag port=$port persona=$persona] ==="
  # ① 独立 mycat-home(每 persona 干净起)
  rm -rf "$HOME_DIR"; mkdir -p "$HOME_DIR/datasources" "$HOME_DIR/users" "$HOME_DIR/clusters"
  cat > "$HOME_DIR/server.json" <<EOF
{"loadBalance":{"defaultLoadBalance":"BalanceRandom","loadBalances":[]},"mode":"local","properties":{"prometheusPort":0},
 "server":{"ip":"0.0.0.0","mycatId":7,"port":8069,"reactorNumber":2,
 "workerPool":{"corePoolSize":1,"keepAliveTime":1,"maxPendingLimit":65535,"maxPoolSize":64,"taskTimeout":5,"timeUnit":"MINUTES"}}}
EOF
  cat > "$HOME_DIR/users/root.user.json" <<'EOF'
{"dialect":"mysql","ip":null,"password":"123456","transactionType":"proxy","username":"root"}
EOF
  # 数据源指向 WSL 对应版本后端
  USERP="123456"
  cat > "$HOME_DIR/datasources/prototypeDs.datasource.json" <<EOF
{"dbType":"mysql","idleTimeout":60000,"initSqls":[],"initSqlsGetConnection":true,"instanceType":"READ_WRITE","maxCon":200,"maxConnectTimeout":30000,"maxRetryCount":5,"minCon":1,"name":"prototypeDs","password":"$USERP","type":"JDBC",
 "url":"jdbc:mysql://127.0.0.1:$port/mysql?useUnicode=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true","user":"root","weight":0}
EOF
  # ② 起 8069(persona 覆盖)
  (cd "$HOME_DIR" && nohup "$MY" --add-opens=java.base/java.nio=ALL-UNNAMED "-Dmycat.persona=$persona" "-DMYCAT_HOME=$HOME_DIR" -jar "D:/Workspaces/Lingma/mycat2/server/target/server-1.22-release-jar-with-dependencies.jar" > "$R/$persona-boot.log" 2>&1 & disown)
  sleep 15
  ready=0
  for i in $(seq 1 40); do (exec 3<>/dev/tcp/127.0.0.1/8069) 2>/dev/null && { exec 3<&- 3>&-; ready=1; break; }; sleep 1; done
  [ "$ready" = 1 ] || { echo "  NO_DATA: 8069 未就绪($persona)"; goto_next; }
  sleep 12
  # ③ compat 四件套(全部类,defaults 即 CI 凭据/db_mycat 指 8069/db1 指 WSL 后端)
  mvn -q -B -pl example test -Dtest="ConnectionSmokeTest,FrontAuthMatrixTest,BackendAuthMatrixTest,CrudResultMatrixTest" -Dsurefire.failIfNoSpecifiedTests=false \
    "-Ddb_mycat=jdbc:mysql://localhost:8069/dbaudit_a?username=root&password=123456&useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true" \
    "-Ddb1=jdbc:mysql://localhost:$port/mysql?username=root&password=123456&useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true" \
    > "$R/$persona-four-piece.log" 2>&1
  RC=$?
  SUM=$(grep -aE "Tests run: [0-9]+, Fail" "$R/$persona-four-piece.log" | tail -1 | grep -aoE "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+")
  echo "  RESULT[$persona]: rc=$RC $SUM"
  echo "$persona,$tag,$port,rc=$RC,$SUM" >> "$R/summary.csv"
  goto_next() { :; }
  # ④ 杀实例
  PID=$(netstat -ano | grep ":8069" | grep LISTENING | head -1 | awk '{print $NF}')
  [ -n "$PID" ] && taskkill //PID $PID //F > /dev/null 2>&1
  sleep 3
done
echo "=== persona 矩阵完成 ==="
cat "$R/summary.csv" 2>/dev/null
