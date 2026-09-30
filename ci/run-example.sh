#!/usr/bin/env bash
# M1 验证网共享脚本：起 Mycat → 等 8066 就绪 → 跑 example + 兼容冒烟测试。
# 端口固定 3306/3307（与 prototypeDs.datasource.json、MycatTest 默认一致），
# 换数据库版本只换容器镜像、不改本脚本、不改 Mycat 配置。
set -euo pipefail

JAR="server/target/server-1.22-release-jar-with-dependencies.jar"
[ -f "$JAR" ] || { echo "找不到构建产物 $JAR（先 mvn package）"; exit 1; }

# 1) 启动 Mycat
java -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8 -jar "$JAR" & MYCAT_PID=$!
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
