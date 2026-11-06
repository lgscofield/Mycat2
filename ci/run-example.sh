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

# 2) 轮询前端 8066 就绪（最多 60s），替代旧 CI 不稳定的 sleep 10s。
#    I-DEBT-79 刀② 教训：曾把此就绪判据从「纯 TCP 可连」升级为「读 5 字节握手包首字节」，
#    但在 GitHub runner 上该 dd 读取路径令 compat step 连续三轮假红（本地 guard 走 ps1、
#    不经此 bash 路径，无法复现定位）。故就绪判据回退到 ffeaad0c2 已验证绿的纯 TCP 可连版本；
#    刀② 的真正价值在下方 persona posture gate（与 MYCAT_PERSONA 绑定），保留。
ready=0
for i in $(seq 1 60); do
  if (exec 3<>"/dev/tcp/127.0.0.1/8066") 2>/dev/null; then
    exec 3<&- 3>&- 2>/dev/null || true; ready=1; break
  fi
  sleep 1
done
[ "$ready" = 1 ] || { echo "Mycat 8066 未就绪"; exit 1; }
# settle：端口可连≠配置/心跳全部就绪，留初始化时间（本地 guard 同口径实测取 15s）
sleep 15

# 2b) I-DEBT-79 刀②：posture gate —— MYCAT_PERSONA 一旦设定，就必须被 select version() 证实。
#     真根因（实测）：-Dmycat.persona 拼错时 PersonaOverride 只记 WARN 并**静默回退**缺省画像，
#     于是「这一档画像」被当成「另一档」测过去 = 假绿（历史上真踩过两次：MYSQL_9_0→5.7、
#     旧元数据矩阵对 mariadb 三档一律盖 MYSQL_5_7）。此处 fail 的是 CI step 本身，不是测试语义。
#     未设 MYCAT_PERSONA 的四件套步保持缺省画像，不做版本断言（避免改动既有绿门禁的语义）。
if [ -n "${MYCAT_PERSONA:-}" ] && command -v mysql >/dev/null 2>&1; then
  case "$MYCAT_PERSONA" in
    MYSQL_5_7) want_ver='^5\.7\.' ;;
    MYSQL_8_0) want_ver='^8\.0\.' ;;
    MYSQL_8_4) want_ver='^8\.4\.' ;;
    MYSQL_9_X) want_ver='^9\.' ;;
    MARIADB_10_6) want_ver='^10\.6\..*MariaDB' ;;
    MARIADB_10_11) want_ver='^10\.11\..*MariaDB' ;;
    MARIADB_11_4) want_ver='^11\.4\..*MariaDB' ;;
    *) echo "ABORT(posture): 非法 MYCAT_PERSONA='$MYCAT_PERSONA'（白名单见 config/ServerPersona.java）"; exit 1 ;;
  esac
  # --get-server-public-key：非 TLS 下 caching_sha2 full-auth 必需，否则 ERROR 2061（仪器坑非产品坑）
  # 重试 3 次防瞬时抖动（读不到与错配同等对待，硬 abort 前给足机会）
  got_ver=""
  for i in 1 2 3; do
    got_ver=$(mysql --protocol=tcp -h127.0.0.1 -P8066 -uroot -p123456 --get-server-public-key \
        -N -B -e 'select version()' 2>/dev/null || echo "")
    [ -n "$got_ver" ] && break
    sleep 3
  done
  echo "POSTURE(persona=$MYCAT_PERSONA) front(8066)=[$got_ver] want=$want_ver"
  # 硬门牙（刀② 收尾，2026-11-06）：WARN 试点轮 run@42171603959 已从 CI 日志取回 7 档真值
  # 且正则逐档命中（5.7.33-mycat-2.0 / 8.0.34 / 8.4.0 / 9.0.1 / 10.6.14-MariaDB /
  # 10.11.8-MariaDB / 11.4.2-MariaDB，全 POSTURE OK），判据经实证，按预定计划升为 abort：
  # 画像与宣称族错配或读不到版本 = 假绿温床，直接红掉本 step，不再放行。
  if [ -z "$got_ver" ]; then
    echo "ABORT(posture): 经 8066 读不到 select version()，无法证实 persona=$MYCAT_PERSONA"; exit 1
  elif ! echo "$got_ver" | grep -qE "$want_ver"; then
    echo "ABORT(posture): 前端 version()='$got_ver' 不在 $MYCAT_PERSONA 宣称的族 $want_ver"; exit 1
  else
    echo "POSTURE OK"
  fi
elif [ -n "${MYCAT_PERSONA:-}" ]; then
  echo "WARN(posture): 无 mysql 客户端，跳过 persona 版本证实（仅握手首字节判据生效）"
fi

# 3) 只跑 example 模块测试（含兼容冒烟 ConnectionSmokeTest），不加 -am 以免
#    reactor 连带跑上游模块测试；上游产物由前置 `mvn install` 落入本地仓库。
mkdir -p target/ci 2>/dev/null || true
mvn -B test -pl example "$@" 2>&1 | tee target/ci/example-test.log
