#!/usr/bin/env bash
set -uo pipefail
cd "D:/Workspaces/IdeaProjects/Mycat2" || exit 9
ROOT=$(mktemp -d)
trap 'rm -rf "$ROOT"' EXIT
# shellcheck source=ci/iopr1/run-live-matrix.sh
source ci/iopr1/run-live-matrix.sh
OUT="$ROOT/classes"
LOGS="$ROOT/logs"
SUMMARY="$ROOT/summary.tsv"
mkdir -p "$ROOT/jdk/bin" "$OUT" "$LOGS"
JDK="$ROOT/jdk"

write_java() {
  cat > "$JDK/bin/java" <<EOF
#!/usr/bin/env bash
$1
EOF
  chmod +x "$JDK/bin/java"
}

fail() { echo "FAIL $*"; exit 1; }
pass() { echo "PASS $*"; }

printf 'tag\tconfig\tverdict\tdetail\n' > "$SUMMARY"
write_java 'echo "RESULT tag=x verdict=PASS"; exit 7'
run probe prod
rc=$?
[ "$rc" -ne 0 ] || fail 'probe 非零退出被忽略'
awk -F'\t' '$1=="probe" && $3=="FAIL" && $4 ~ /rc=7/{ok=1} END{exit !ok}' "$SUMMARY" \
  || fail 'probe 非零退出未记 FAIL'
pass 'probe 非零退出传播到汇总与返回码'

write_java 'echo "RESULT tag=x verdict=PASS"; exit 0'
run probe prod || fail '正常 PASS 探针返回非零'
awk -F'\t' '$1=="probe" && $3=="PASS"{ok=1} END{exit !ok}' "$SUMMARY" \
  || fail '正常 PASS 未保留'
pass '正常 PASS 探针通过'

write_java 'echo "no result"; exit 0'
run probe prod && fail '无 RESULT 探针返回零'
awk -F'\t' '$1=="probe" && $3=="NO_RESULT"{ok=1} END{exit !ok}' "$SUMMARY" \
  || fail '无 RESULT 未记 NO_RESULT'
pass '无 RESULT 探针失败'

write_java 'echo "RESULT tag=x verdict=FAIL"; exit 1'
run probe prod && fail 'FAIL 探针返回零'
pass 'FAIL 探针返回非零'

write_summary() {
  printf 'tag\tconfig\tverdict\tdetail\n' > "$SUMMARY"
  for tag in prod-setup prod-load prod-threads prod-seq prod-ddl prod-r4 \
    small-setup small-saturate small-ops small-seq-fit small-seq-sat \
    small-deadline small-abandon small-r5 L1-pinning L7-heartbeat; do
    printf '%s\ttest\tPASS\t%s\n' "$tag" "$1" >> "$SUMMARY"
  done
}
write_summary 'heartbeatAdvanced=true'
verify_summary "$SUMMARY" || fail '完整 16 格 PASS 被拒绝'
pass '完整 16 格 PASS 通过'

awk -F'\t' '$1=="small-r5"{$3="FAIL"} {print}' OFS='\t' "$SUMMARY" > "$ROOT/bad.tsv"
verify_summary "$ROOT/bad.tsv" && fail 'FAIL 汇总返回零'
pass 'FAIL 汇总返回非零'

awk -F'\t' '$1!="small-r5"' "$SUMMARY" > "$ROOT/missing.tsv"
verify_summary "$ROOT/missing.tsv" && fail '缺格汇总返回零'
pass '缺格汇总返回非零'

awk -F'\t' '$1!="L7-heartbeat" && $1!="prod-setup"' "$SUMMARY" > "$ROOT/missing-extra.tsv"
printf 'unexpected\ttest\tPASS\tx\n' >> "$ROOT/missing-extra.tsv"
verify_summary "$ROOT/missing-extra.tsv" && fail '错格汇总返回零'
pass '错格汇总返回非零'

: > "$LOGS/prod-server.log"
: > "$LOGS/small-server.log"
heartbeat_verdict 3 'RESULT tag=small-ops heartbeatAdvanced=true verdict=PASS'
[ "$?" = 0 ] || fail '有心跳进展的正断言被拒绝'
pass '心跳进展正断言通过'

heartbeat_verdict 3 'RESULT tag=small-ops heartbeatAdvanced=false verdict=PASS'
[ "$?" != 0 ] || fail '无心跳进展仍通过'
pass '无心跳进展拒绝'

echo 'heartbeat error backend dead' > "$LOGS/small-server.log"
heartbeat_verdict 3 'RESULT tag=small-ops heartbeatAdvanced=true verdict=PASS' 1
[ "$?" != 0 ] || fail '心跳错误仍通过'
pass '心跳错误拒绝'

echo 'DRIVER_TESTS_DONE'
