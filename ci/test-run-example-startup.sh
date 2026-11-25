#!/usr/bin/env bash
set -uo pipefail
cd "D:/Workspaces/IdeaProjects/Mycat2" || exit 9

ROOT=$(mktemp -d)
PIDS=()

stop_pidfile() {
  local file="$1" pid=""
  [ -f "$file" ] || return 0
  pid=$(<"$file")
  [ -n "$pid" ] || return 0
  if command -v taskkill >/dev/null 2>&1; then
    local winpid=""
    winpid=$(ps -l -p "$pid" 2>/dev/null | awk 'NR>1 {print $4}' || true)
    taskkill //PID "${winpid:-$pid}" //T //F >/dev/null 2>&1 || true
  else
    kill "$pid" 2>/dev/null || true
  fi
}

cleanup() {
  local file
  for file in "$ROOT"/*.pid; do
    [ -f "$file" ] && stop_pidfile "$file"
  done
  rm -rf "$ROOT"
}
trap cleanup EXIT

fail() { echo "FAIL $*"; exit 1; }
pass() { echo "PASS $*"; }

port_ready() {
  (exec 3<>"/dev/tcp/127.0.0.1/8066") 2>/dev/null
}

wait_for_port() {
  local i
  for i in $(seq 1 30); do
    if port_ready; then return 0; fi
    sleep 0.1
  done
  return 1
}

wait_for_port_release() {
  local i
  for i in $(seq 1 100); do
    if ! port_ready; then return 0; fi
    sleep 0.1
  done
  return 1
}

command -v node >/dev/null 2>&1 || fail "需要 node 来构造真实 TCP 监听"
port_ready && fail "测试前置失败：8066 已被未知进程占用"

mkdir -p "$ROOT/bin"
# Polyglot wrapper execs Node so Git Bash $! matches the PID used by taskkill.
cat > "$ROOT/bin/java" <<'EOF'
#!/bin/sh
// 2>/dev/null; exec node "$0" "$@"
const fs = require('fs');
const net = require('net');
const mode = process.env.FAKE_JAVA_MODE;
fs.writeFileSync(process.env.FAKE_JAVA_PIDFILE, String(process.pid));
fs.appendFileSync(process.env.FAKE_JAVA_MARKER, 'invoked\n');
if (mode === 'exit') process.exit(1);
const server = net.createServer(socket => socket.end());
server.on('error', err => {
  fs.appendFileSync(process.env.MYCAT_SERVER_LOG, String(err) + '\n');
  process.exit(1);
});
server.listen(8066, '127.0.0.1', () => {
  if (mode === 'ready') {
    fs.appendFileSync(process.env.MYCAT_SERVER_LOG, 'Mycat Vertx server fake started up.\n');
  }
});
process.on('SIGTERM', () => server.close(() => process.exit(0)));
setTimeout(() => process.exit(0), 20000);
EOF
chmod +x "$ROOT/bin/java"

cat > "$ROOT/bin/mysql" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "$ROOT/bin/mysql"

cat > "$ROOT/bin/mvn" <<'EOF'
#!/usr/bin/env bash
printf 'invoked\n' >> "$FAKE_MVN_MARKER"
exit 0
EOF
chmod +x "$ROOT/bin/mvn"

run_example() {
  local name="$1" mode="$2"
  local out="$ROOT/$name.out"
  local java_marker="$ROOT/$name.java" mvn_marker="$ROOT/$name.mvn"
  local pidfile="$ROOT/$name.java.pid" server_log="$ROOT/$name.server.log"
  : > "$java_marker"
  : > "$mvn_marker"
  : > "$server_log"
  set +e
  timeout 15s env \
    PATH="$ROOT/bin:$PATH" \
    MYCAT_PORT_RELEASE_WAIT_SECONDS=1 \
    MYCAT_STARTUP_WAIT_SECONDS=2 \
    MYCAT_SETTLE_SECONDS=0 \
    MYCAT_SERVER_LOG="$server_log" \
    FAKE_JAVA_MODE="$mode" \
    FAKE_JAVA_PIDFILE="$pidfile" \
    FAKE_JAVA_MARKER="$java_marker" \
    FAKE_MVN_MARKER="$mvn_marker" \
    bash ci/run-example.sh >"$out" 2>&1
  CASE_RC=$?
  CASE_OUT="$out"
  CASE_JAVA_MARKER="$java_marker"
  CASE_MVN_MARKER="$mvn_marker"
  CASE_PIDFILE="$pidfile"
  set -e
}

set -e
node -e 'const net=require("net");const s=net.createServer(c=>c.end());s.listen(8066,"127.0.0.1");setInterval(()=>{},1000)' \
  >"$ROOT/stale.out" 2>&1 &
STALE_PID=$!
printf '%s\n' "$STALE_PID" > "$ROOT/stale.pid"
wait_for_port || fail "残留监听夹具未就绪"
run_example stale-port ready
[ "$CASE_RC" -ne 0 ] || fail "8066 残留监听时 run-example 仍返回零"
grep -qE 'ABORT\(startup\).*8066' "$CASE_OUT" || fail "残留端口未给出明确启动 abort"
[ ! -s "$CASE_JAVA_MARKER" ] || fail "端口预检前仍启动了新 Mycat"
[ ! -s "$CASE_MVN_MARKER" ] || fail "启动失败后仍运行了 example 测试"
stop_pidfile "$ROOT/stale.pid"
wait_for_port_release || fail "残留监听夹具停止后 8066 未释放"
pass '残留 8066 监听在启动前被拒绝'

run_example missing-marker listen
[ "$CASE_RC" -ne 0 ] || fail "只有 TCP 可连、无 started up 标记时仍返回零"
grep -qE 'ABORT\(startup\).*完成监听启动' "$CASE_OUT" || fail "假就绪未给出明确启动 abort：$(tail -n 40 "$CASE_OUT")"
[ ! -s "$CASE_MVN_MARKER" ] || fail "假就绪后仍运行了 example 测试"
wait_for_port_release || fail "假就绪用例退出后 8066 未释放"
pass 'TCP 可连但本轮进程未 started up 时拒绝放行'

run_example early-exit exit
[ "$CASE_RC" -ne 0 ] || fail "Mycat 进程早退仍返回零"
grep -qE 'ABORT\(startup\).*提前退出' "$CASE_OUT" || fail "进程早退未给出明确启动 abort"
[ ! -s "$CASE_MVN_MARKER" ] || fail "进程早退后仍运行了 example 测试"
wait_for_port_release || fail "进程早退用例退出后 8066 未释放"
pass 'Mycat 进程早退被立即识别'

run_example success ready
[ "$CASE_RC" -eq 0 ] || fail "正常启动夹具被拒绝：$(tail -n 20 "$CASE_OUT")"
[ -s "$CASE_MVN_MARKER" ] || fail "正常启动后未运行 example 测试"
grep -q 'Mycat Vertx server fake started up\.' "$ROOT/success.server.log" || fail "本轮启动标记未写入服务日志"
wait_for_port_release || fail "run-example 退出后仍遗留 8066 监听"
pass '正常启动放行且退出清理监听'

echo 'RUN_EXAMPLE_STARTUP_TESTS_DONE'
