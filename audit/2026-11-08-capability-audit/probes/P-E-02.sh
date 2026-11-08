#!/usr/bin/env bash
# PROBE: P-E-02 mysqldump 真二进制经 mycat 导出 → 回灌 3306 直连库 → 行级对账
# 验证 M3 "dump ~100%" 声明。对象: dbaudit_shard.sb(P-B-01 遗留 110 行真实分片数据)
LOG="D:/Workspaces/Lingma/mycat2/audit/2026-11-08-capability-audit/logs/P-E-02.log"
DUMP="D:/audit-dump.sql"
{
echo "--- mysqldump via mycat 8066 ---"
mysqldump --no-tablespaces --skip-lock-tables --set-gtid-purged=OFF --column-statistics=0 \
  -h127.0.0.1 -P8066 -uroot -p123456 dbaudit_shard sb > "$DUMP" 2>"$LOG.dumperr"
echo "dump rc=$? size=$(wc -c < "$DUMP" 2>/dev/null)"
head -c 300 "$DUMP"; echo
echo "--- dump stderr ---"; cat "$LOG.dumperr"
echo "--- 回灌到 3306 dbaudit_b ---"
mysql -h127.0.0.1 -P3306 -uroot -proot dbaudit_b < "$DUMP" 2>&1
echo "restore rc=$?"
echo "--- 行数对账 ---"
mysql -h127.0.0.1 -P3306 -uroot -proot -N -B -e "SELECT 'via_mycat_dump_restored', COUNT(*) FROM dbaudit_b.sb" 2>/dev/null
mysql --protocol=tcp -h127.0.0.1 -P8066 -uroot -p123456 --get-server-public-key -N -B -e "SELECT 'source_on_mycat', COUNT(*) FROM dbaudit_shard.sb" 2>/dev/null
echo "--- 清理 ---"
mysql -h127.0.0.1 -P3306 -uroot -proot -e "DROP TABLE IF EXISTS dbaudit_b.sb" 2>/dev/null
} > "$LOG" 2>&1
cat "$LOG"
src=$(mysql --protocol=tcp -h127.0.0.1 -P8066 -uroot -p123456 --get-server-public-key -N -B -e "SELECT COUNT(*) FROM dbaudit_shard.sb" 2>/dev/null)
dst=$(mysql -h127.0.0.1 -P3306 -uroot -proot -N -B -e "SELECT COUNT(*) FROM dbaudit_b.sb" 2>/dev/null)
echo "source=$src restored=$dst"
if [ -n "$src" ] && [ "$src" = "$dst" ] && [ "$src" != "0" ]; then echo "VERDICT: PASS"; exit 0; fi
if [ "$src" = "$dst" ] && [ -n "$src" ]; then echo "VERDICT: PASS"; exit 0; fi
echo "VERDICT: RED"; exit 1
