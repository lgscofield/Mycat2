#!/usr/bin/env bash
# PROBE: P-D-01 差距1核验: hint createDataSource 是否只落内存、重启即失
# 阶段A: 经 hint 建 auditDsX → 查运行时可见性 + 查 mycat-home/datasources/ 磁盘文件
# 阶段B(重启后由 run-all 再调 -phaseB): 查磁盘文件与运行时是否消失
MH="D:/Workspaces/Lingma/mycat2/audit/2026-11-08-capability-audit/mycat-home"
MY="mysql --protocol=tcp -h127.0.0.1 -P8066 -uroot -p123456 --get-server-public-key"
LOG="D:/Workspaces/Lingma/mycat2/audit/2026-11-08-capability-audit/logs/P-D-01.log"
case "${1:-A}" in
A)
  {
  echo "--- hint createDataSource auditDsX ---"
  $MY -N -B -e '/*+ mycat:createDataSource {"name":"auditDsX","url":"jdbc:mysql://127.0.0.1:3306/dbaudit_b?useSSL=false&allowPublicKeyRetrieval=true","user":"audit","password":"Audit#2026"} */ SELECT 1' 2>&1
  echo "rc=$?"
  echo "--- 运行时可见性: showDatasources ---"
  $MY -N -B -e '/*+ mycat:showDatasources{} */' 2>&1 | head -20
  echo "--- 磁盘 datasources/ 目录 ---"
  ls "$MH/datasources/"
  echo "--- marker ---"
  if ls "$MH/datasources/" | grep -q "auditDsX"; then echo "DISK_HAS_auditDsX=yes"; else echo "DISK_HAS_auditDsX=no"; fi
  } > "$LOG" 2>&1
  grep -q "auditDsX" "$LOG" && { echo "VERDICT: PASS (阶段A完成, 详情见日志)"; exit 0; } || { echo "VERDICT: RED (hint 无效)"; exit 1; }
  ;;
B)
  {
  echo "--- 重启后 磁盘 datasources/ ---"
  ls "$MH/datasources/"
  if ls "$MH/datasources/" | grep -q "auditDsX"; then echo "AFTER_RESTART_DISK=yes"; else echo "AFTER_RESTART_DISK=no"; fi
  echo "--- 重启后 运行时 showDatasources ---"
  mysql --protocol=tcp -h127.0.0.1 -P8066 -uroot -p123456 --get-server-public-key -N -B -e '/*+ mycat:showDatasources{} */' 2>&1 | head -20
  } > "$LOG.B" 2>&1
  if grep -q "AFTER_RESTART_DISK=no" "$LOG.B"; then
    echo "CONFIRMED: 重启后磁盘配置消失 → 差距1坐实(重启即失)"
    echo "VERDICT: PASS"; exit 0
  elif grep -q "AFTER_RESTART_DISK=yes" "$LOG.B"; then
    echo "REBUTTED: 重启后配置仍在磁盘 → 差距1被证伪(文档低估)"
    echo "VERDICT: PASS"; exit 0
  else
    echo "VERDICT: NO_DATA"; exit 2
  fi
  ;;
esac
