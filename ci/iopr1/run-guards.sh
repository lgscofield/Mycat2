#!/usr/bin/env bash
set -uo pipefail
cd "D:/Workspaces/IdeaProjects/Mycat2" || exit 9
export JAVA_HOME="D:/Program Files/BellSoft/LibericaJDK-21-Full"
export PATH="$JAVA_HOME/bin:$PATH"
LOGS="${GUARD_LOGS:-ci/iopr1/guard-logs}"
mkdir -p "$LOGS"
FAILED=0

run_stage() {
  local name="$1"; shift
  local log="$LOGS/$name.log"
  echo "=== GUARD_START $name ==="
  "$@" >"$log" 2>&1
  local rc=$?
  echo "=== GUARD_EXIT $name rc=$rc ==="
  if [ "$rc" -ne 0 ]; then FAILED=1; fi
}

run_stage run-example-startup bash ci/test-run-example-startup.sh
run_stage live-matrix-driver bash ci/iopr1/test-live-matrix-driver.sh
run_stage reactor mvn -B -pl server,hbt -am test

run_stage m1-m2-mysql57 env MYCAT_PERSONA=MYSQL_5_7 bash ci/run-example.sh
for persona in MYSQL_5_7 MYSQL_8_0 MYSQL_8_4 MYSQL_9_X MARIADB_10_6 MARIADB_10_11 MARIADB_11_4; do
  run_stage "m3-m4-$persona" env MYCAT_PERSONA="$persona" bash ci/run-example.sh \
    -Dtest=DumpSurfaceMatrixTest,DumpRestoreRoundTripMatrixTest,MigrateMatrixTest
 done
run_stage old-connector-stage6-tls bash ci/verify-old-connector.sh

echo "=== GUARDS_DONE failed=$FAILED ==="
exit "$FAILED"
