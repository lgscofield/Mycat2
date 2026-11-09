#!/usr/bin/env bash
# 通宵自主推进链: 3309 后端 → G8 重跑 → G6 隔离长稳;全程落 OVERNIGHT-STATUS.md
set -u
A="D:/Workspaces/Lingma/mycat2/audit/2026-11-08-capability-audit"
JDK="D:/Program Files/BellSoft/LibericaJDK-21-Full"
MY="$JDK/bin/java"
MYSQL="/d/Program Files/MySQL/MySQL Server 8.4/bin/mysql.exe"
MYSQLD="/d/Program Files/MySQL/MySQL Server 8.4/bin/mysqld.exe"
S="$A/OVERNIGHT-STATUS.md"
log(){ echo "[$(date +%H:%M:%S)] $*" >> "$S"; }

{
echo "# 通宵自主推进晨报($(date +%F))"
echo
echo "## 轮1 · 3309 后端"
} >> "$S"
DD="$A/db3309"
[ -d "$DD/mysql" ] || "$MYSQLD" --no-defaults --initialize-insecure --datadir="D:\\Workspaces\\Lingma\\mycat2\\audit\\2026-11-08-capability-audit\\db3309" --console > "$A/logs/N1-init3309.log" 2>&1
cat > /tmp/init3309.sql <<'EOF'
CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED BY '123456';
GRANT ALL ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION;
CREATE USER IF NOT EXISTS 'root'@'%' IDENTIFIED BY '123456';
GRANT ALL ON *.* TO 'root'@'%' WITH GRANT OPTION;
ALTER USER 'root'@'localhost' IDENTIFIED BY '123456';
SET GLOBAL max_connections=1000;
FLUSH PRIVILEGES;
EOF
"$MYSQLD" --no-defaults --datadir="D:\\Workspaces\\Lingma\\mycat2\\audit\\2026-11-08-capability-audit\\db3309" --port=3309 --mysqlx=OFF --skip-name-resolve --console --init-file="C:\\Users\\SEELE\\AppData\\Local\\Temp\\init3309.sql" > "$A/logs/N1-run3309.log" 2>&1 &
sleep 12
"$MYSQL" -h127.0.0.1 -P3309 -uroot -p123456 -N -B -e "SELECT VERSION(),@@port; CREATE DATABASE IF NOT EXISTS db1; CREATE DATABASE IF NOT EXISTS db2;" >> "$S" 2>&1 && log "轮1 PASS: 3309 就绪(root/123456, db1/db2)" || log "轮1 RED"

{
echo
echo "## 轮2 · G8 全量重跑(8068)"
} >> "$S"
PID=$(netstat -ano | grep ":8068" | grep LISTENING | head -1 | awk '{print $NF}'); [ -n "$PID" ] || {
  cd "$A/suite-home" && nohup "$MY" --add-opens=java.base/java.nio=ALL-UNNAMED "-DMYCAT_HOME=$A\\suite-home" -jar "D:/Workspaces/Lingma/mycat2/server/target/server-1.22-release-jar-with-dependencies.jar" > "$A/logs/N2-boot8068.log" 2>&1 & disown
  sleep 20
}
for i in $(seq 1 40); do (exec 3<>/dev/tcp/127.0.0.1/8068) 2>/dev/null && { exec 3<&- 3>&-; break; }; sleep 1; done
sleep 10
cd "D:/Workspaces/Lingma/mycat2"
export JAVA_HOME="$JDK"; export PATH="$JDK/bin:$PATH"
mvn -B -pl example -am test -Dsurefire.failIfNoSpecifiedTests=false "-Ddb_mycat=jdbc:mysql://localhost:8068/dbaudit_a?username=root&password=123456&useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true" "-Ddb1=jdbc:mysql://localhost:3307/mysql?username=root&password=123456&useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true" "-Ddb2=jdbc:mysql://localhost:3309/mysql?username=root&password=123456&useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true" > "$A/logs/G8-rerun.log" 2>&1
G8RC=$?
{
echo "G8_EXIT=$G8RC"
grep -aE "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$" "$A/logs/G8-rerun.log" | tail -2
grep -a "BUILD" "$A/logs/G8-rerun.log" | tail -1
echo "红项按类:"; grep -aE "<<< (FAILURE|ERROR)!" "$A/logs/G8-rerun.log" | grep -aoE "in io\.mycat\.[A-Za-z0-9_.]+" | sort | uniq -c | sort -rn | head -12
} >> "$S"
log "轮2 完成: G8_EXIT=$G8RC(红项定性留晨会)"

{
echo
echo "## 轮3 · G6 隔离长稳 30min(对 8066)"
} >> "$S"
cd "$A/probes"
"$MY" -cp "D:/Workspaces/Lingma/mycat2/server/target/server-1.22-release-jar-with-dependencies.jar" "G6-soak30m.java" > "$A/logs/G6-rerun.log" 2>&1
G6RC=$?
{
echo "G6_EXIT=$G6RC"
grep -aE "SOAK DONE|RESULT" "$A/logs/G6-rerun.log" | tail -2
grep -aE "^t\+[0-9]+min" "$A/logs/G6-rerun.log" | tail -4
} >> "$S"
log "轮3 完成: G6_EXIT=$G6RC"
echo
echo "## 晨会结论" >> "$S"
echo "红项定性/修票/账本更新由下一会话按本文件+ledger 执行。" >> "$S"
