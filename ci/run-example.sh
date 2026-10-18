#!/usr/bin/env bash
# M1 验证网共享脚本：起 Mycat → 等 8066 就绪 → 跑 example + 兼容冒烟测试。
# 端口固定 3306/3307（与 prototypeDs.datasource.json、MycatTest 默认一致），
# 换数据库版本只换容器镜像、不改本脚本、不改 Mycat 配置。
set -euo pipefail

JAR="server/target/server-1.22-release-jar-with-dependencies.jar"
[ -f "$JAR" ] || { echo "找不到构建产物 $JAR（先 mvn package）"; exit 1; }

# I-DEBT-7B 后端连接容量前置：Mycat 后端池 maxCon 默认 1000（config/DatasourceConfig.java），
# 而 MySQL/MariaDB 服务默认 max_connections=151。e2e 分片扇出（如 tbpartitions 12）+ 空闲保活
# (keepAlive=true) 会在 MySQL 侧累积到 >151，触发 "Too many connections" → createError 递增
# → 客户端借连接阻塞至 maxWait → GetConnectionTimeoutException 长挂（SqlFunctionTest 每法曾挂 ~110s，
# 抬到 1000 后 9.4s 跑完）。负载停后连接可回落（实测回落=2），非产品泄漏，故属测试环境容量前置假设，
# 与本机 I-DEBT-19 Arrow --add-opens 同类。best-effort：无 mysql 客户端则跳过（本地可用
#   docker exec m1-mysql-a mysql -uroot -p123456 -e 'SET GLOBAL max_connections=1000' 手动抬升）。
if command -v mysql >/dev/null 2>&1; then
  for p in 3306 3307; do
    mysql --protocol=tcp -h127.0.0.1 -P"$p" -uroot -p123456 \
      -e "SET GLOBAL max_connections=1000;" 2>/dev/null \
      && echo "抬升 127.0.0.1:$p max_connections=1000 (I-DEBT-7B)" \
      || echo "跳过 127.0.0.1:$p（不可达或无权限，I-DEBT-7B 需手动抬 max_connections）"
  done
fi

# 1) 启动 Mycat
# MYCAT_PERSONA：M3/M4 专项套件步的 persona 启动覆盖（与本地 run-mig-persona.ps1 等同口径，
# dump 面版本门/迁移 DDL 方言随画像变）。置空则保持缺省 MYSQL_5_7，既有四件套 CI 行为不变。
PERSONA_ARG=""
if [ -n "${MYCAT_PERSONA:-}" ]; then PERSONA_ARG="-Dmycat.persona=${MYCAT_PERSONA}"; fi
# --add-opens=java.base/java.nio=ALL-UNNAMED: Apache Arrow 18 (vector() 算子用其堆外内存,
# MemoryUtil 反射 java.nio.Buffer.address) 在 JDK17+ 必需,否则 VectorTest 挂死。
java $PERSONA_ARG --add-opens=java.base/java.nio=ALL-UNNAMED -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8 -jar "$JAR" & MYCAT_PID=$!
trap 'kill $MYCAT_PID 2>/dev/null || true' EXIT

# 2) 轮询前端 8066 就绪（最多 60s），替代旧 CI 不稳定的 sleep 10s
ready=0
for i in $(seq 1 60); do
  if (exec 3<>"/dev/tcp/127.0.0.1/8066") 2>/dev/null; then
    exec 3<&- 3>&-; ready=1; break
  fi
  sleep 1
done
[ "$ready" = 1 ] || { echo "Mycat 8066 未就绪"; exit 1; }

# 3) 只跑 example 模块测试（含兼容冒烟 ConnectionSmokeTest），不加 -am 以免
#    reactor 连带跑上游模块测试；上游产物由前置 `mvn install` 落入本地仓库。
mkdir -p target/ci 2>/dev/null || true
mvn -B test -pl example "$@" 2>&1 | tee target/ci/example-test.log
