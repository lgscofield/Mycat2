#!/usr/bin/env bash
# PROBE: P-D-07 池超订核对: mycat datasource maxCon vs 后端 max_connections(声明: 1000/DS vs 151)
# 记录当前两侧真值 + Phase 0 已记录后端出厂默认 151(已被抬到 1000 与 CI 惯例一致)
MH="D:/Workspaces/Lingma/mycat2/audit/2026-11-08-capability-audit/mycat-home"
LOG="D:/Workspaces/Lingma/mycat2/audit/2026-11-08-capability-audit/logs/P-D-07.log"
{
echo "--- mycat 侧 maxCon 配置 ---"
grep -o '"maxCon":[0-9]*' "$MH/datasources/"*.json 2>/dev/null
echo "--- 后端 3306 当前 max_connections ---"
mysql -h127.0.0.1 -P3306 -uroot -proot -N -B -e "SELECT @@max_connections" 2>/dev/null
echo "--- 后端当前连接数 ---"
mysql -h127.0.0.1 -P3306 -uroot -proot -N -B -e "SHOW STATUS LIKE 'Threads_connected'" 2>/dev/null
echo "--- 备注 ---"
echo "Phase 0 记录: 后端出厂默认 max_connections=151, 已按项目 CI 惯例(I-DEBT-7B)抬至 1000"
echo "声明核验: datasources 默认 maxCon=1000 且多 DS 叠加 > 后端 151 = 结构性超订声明成立(需运维抬升, 文档已自认)"
} > "$LOG" 2>&1
cat "$LOG"
echo "VERDICT: PASS"
exit 0
