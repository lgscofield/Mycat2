#!/usr/bin/env bash
# PROBE: P-E-04 读写分离/副本路由
# 需要 3307/3308 第二后端 —— Phase 0 探测均未监听 → NO_DATA(如实)
LOG="D:/Workspaces/Lingma/mycat2/audit/2026-11-08-capability-audit/logs/P-E-04.log"
{
echo "checking 3307/3308..."
for p in 3307 3308; do
  if (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null; then exec 3<&- 3>&-; echo "port $p OPEN"; else echo "port $p closed"; fi
done
echo "NO_DATA: 第二后端未就绪(用户将起但未起), 读写分离/副本延迟/双实例故障面本轮无法实证"
} > "$LOG" 2>&1
cat "$LOG"
echo "VERDICT: NO_DATA"; exit 2
