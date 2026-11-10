#!/usr/bin/env bash
# 收官流水线: 本地批次全部完成后一键执行
# ① 重建 fat jar(含 97/99/F3/F8 修复) ② 重启 8066/8068 ③ 活体验收 97(1/0 告警)+99(sql_mode 传导)
# ④ G6 隔离长稳 30 分钟 ⑤ 结果汇总
set -u
A="D:/Workspaces/Lingma/mycat2/audit/2026-11-08-capability-audit"
JDK="D:/Program Files/BellSoft/LibericaJDK-21-Full"
MY="$JDK/bin/java"
MYSQL="/d/Program Files/MySQL/MySQL Server 8.4/bin/mysql.exe"
S="$A/OVERNIGHT-STATUS.md"
log(){ echo "[$(date +%H:%M:%S)] $*" >> "$S"; }

log "=== 收官流水线开始 ==="
# ① 重打 jar
cd /d/Workspaces/Lingma/mycat2
export JAVA_HOME="$JDK"; export PATH="$JDK/bin:$PATH"
mvn -q -B -pl server,va -am package -DskipTests > "$A/logs/F1-rebuild.log" 2>&1
log "① jar 重建 EXIT=$?"
# ② 重启实例(8066 主实例带全部修复)
for P in 8066 8068; do PID=$(netstat -ano | grep ":$P" | grep LISTENING | head -1 | awk '{print $NF}'); [ -n "$PID" ] && taskkill //PID $PID //F > /dev/null 2>&1; done
sleep 3
(cd "$A/mycat-home" && nohup "$MY" --add-opens=java.base/java.nio=ALL-UNNAMED "-DMYCAT_HOME=$A\\mycat-home" -jar "D:/Workspaces/Lingma/mycat2/server/target/server-1.22-release-jar-with-dependencies.jar" > "$A/logs/F2-boot8066.log" 2>&1 & disown)
sleep 16
for i in $(seq 1 40); do (exec 3<>/dev/tcp/127.0.0.1/8066) 2>/dev/null && { exec 3<&- 3>&-; break; }; sleep 1; done
sleep 12
# ③ 活体验收 97(1/0 → warning_count=1)+ 99(sql_mode 传导 → 截断插入成功)
R97=$("$MYSQL" --protocol=tcp -h127.0.0.1 -P8066 -uroot -p123456 --get-server-public-key -N -B 2>/dev/null <<'EOF'
SELECT 1/0;
SELECT @@warning_count;
EOF
)
log "③a 97 活体: 1/0 与 warning_count = $(echo "$R97" | tr '\n' ' ') (期望 NULL 与 1)"
R99=$("$MYSQL" --protocol=tcp -h127.0.0.1 -P8066 -uroot -p123456 --get-server-public-key -N -B 2>/dev/null <<'EOF'
CREATE DATABASE IF NOT EXISTS dbaudit_shard3;
CREATE TABLE IF NOT EXISTS dbaudit_shard3.sv (v varchar(5)) ;
SET sql_mode='';
INSERT INTO dbaudit_shard3.sv VALUES ('0123456789abc');
SELECT 'INSERT-SURVIVED' AS r;
DROP TABLE dbaudit_shard3.sv;
EOF
)
echo "$R99" | grep -q "INSERT-SURVIVED" && log "③b 99 活体: sql_mode 传导 PASS(截断插入经会话值对齐后成功)" || log "③b 99 活体: RED(传导未生效)——$(echo "$R99" | head -1)"
echo "$R97" | tail -1 | grep -q "^1$" && log "③c 97 判定: PASS" || log "③c 97 判定: RED"
# ④ G6 隔离长稳 30 分钟(8066 现在健康且含全部修复)
cd "$A/probes"
"$MY" -cp "D:/Workspaces/Lingma/mycat2/server/target/server-1.22-release-jar-with-dependencies.jar" "G6-soak30m.java" > "$A/logs/G6-final.log" 2>&1
log "④ G6 隔离长稳 EXIT=$? ($(grep -aE 'SOAK DONE|RESULT' "$A/logs/G6-final.log" | tail -2 | tr '\n' ' '))"
log "=== 收官流水线结束 ==="
echo "RESULTS: 97=$(grep -c '^1$' <<<"$R97") 99=$(echo "$R99" | grep -c 'INSERT-SURVIVED') G6=$(grep -aE 'RESULT' "$A/logs/G6-final.log" | tail -1)"
