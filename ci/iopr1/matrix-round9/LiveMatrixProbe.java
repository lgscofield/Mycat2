import io.mycat.config.DatasourceConfig;
import io.mycat.config.ShardingBackEndTableInfoConfig;
import io.mycat.config.ShardingFunction;
import io.mycat.hint.CreateClusterHint;
import io.mycat.hint.CreateDataSourceHint;
import io.mycat.hint.CreateSchemaHint;
import io.mycat.hint.CreateTableHint;
import io.mycat.hint.InterruptThreadHint;
import io.mycat.hint.KillThreadHint;
import io.mycat.hint.ShowThreadInfoHint;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * I-PR-1 Task 6 活体矩阵探针。经 8066 前端驱动真实 SQL，证明 VT 换轨 + 准入闸的运行时语义。
 *
 * <p>用法：java -cp fatjar;classes LiveMatrixProbe MODE [args...]
 * MODE = setup | load | saturate | ops | seq | deadline | abandon | threads | ddl | http-rule | sql-log
 *
 * <p>输出契约沿用 ci/vtspike 房型：单行 {@code RESULT tag=... key=value ...}，
 * 失败时 verdict=FAIL 并以非零码退出；跑不到结论时 verdict=NO_DATA（绝不伪绿）。
 *
 * <p>hint 一律走 execute()+getResultSet()：Connector/J 对无标准起始关键字的 hint
 * 用 executeQuery() 会抛 "cannot issue statements that do not produce result sets"。
 */
public class LiveMatrixProbe {

    static String host = "127.0.0.1";
    static String frontPort = "8066";
    static String user = "root";
    static String pass = "123456";
    static String backPortA = "3306";
    static String backPortB = "3307";

    static final String SCHEMA = "dbiopr1";
    static final String SHORT_SCHEMA = "dbshort";
    static final String DS = "prototypeDs";
    static final String SHORT_DS = "shortDs";
    static final String CLUSTER = "prototype";
    // 短 deadline 集群名**绝不能以 c 开头**：MetadataManager.getDefaultStoreNodeNum()(:506-514)
    // 按 startsWith("c") 数集群个数当 storeNum，而 AutoFunctionFactory(:98-101,:414-427) 用
    // Groovy 模板 "c${targetIndex}" **按序号 positional 生成**分片目标名（c0..c(storeNum-1)），
    // 不是映射到既有集群名。叫 cshort 会把 storeNum 抬到 2 ⇒ 第 2、3 号分片去找根本不存在的 c1
    // ⇒ CreateTableUtils:54 "can not found c1"。用非 c 前缀，storeNum 保持 1（只有 defaultConfig 的 c0），
    // 分片表全部落 c0→prototypeDs；本集群只作为 dbshort 普通表的 targetName 按名解析。
    static final String SHORT_CLUSTER = "sht";
    static final String TABLE = "travelrecord";
    static final String SEQ_NAME = SCHEMA + "_" + TABLE;

    static final String RESET_CONFIG = "/*+ mycat:resetConfig{} */";
    static final String SET_SEQUENCE = "/*+ mycat:setSequence{"
            + "\"name\":\"" + SEQ_NAME + "\","
            + "\"clazz\":\"io.mycat.plug.sequence.SequenceMySQLGenerator\","
            + "\"targetName\":\"" + CLUSTER + "\","
            + "\"schemaName\":\"" + SCHEMA + "\"} */;";

    static final String TABLE_DDL = "CREATE TABLE " + SCHEMA + "." + TABLE + " (\n"
            + "  `id` bigint NOT NULL AUTO_INCREMENT,\n"
            + "  `user_id` varchar(100) DEFAULT NULL,\n"
            + "  `fee` decimal(10,0) DEFAULT NULL,\n"
            + "  PRIMARY KEY (`id`)\n"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8"
            + " dbpartition by mod_hash(id) tbpartition by mod_hash(id) tbpartitions 2 dbpartitions 2";

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "setup";
        if (args.length > 1) host = args[1];
        if (args.length > 2) frontPort = args[2];
        Class.forName("com.mysql.cj.jdbc.Driver");
        String tag = mode;
        StringBuilder kv = new StringBuilder();
        String verdict;
        try {
            switch (mode) {
                case "setup": setup(); kv.append("setup=OK"); verdict = "PASS"; break;
                case "load": verdict = load(kv, arg(args, 3, 32), arg(args, 4, 20)); break;
                case "saturate": verdict = saturate(kv, arg(args, 3, 3), arg(args, 4, 6)); break;
                case "ops": verdict = ops(kv, arg(args, 3, 3), arg(args, 4, 8)); break;
                case "seq": verdict = seq(kv, arg(args, 3, 8), arg(args, 4, 60), arg(args, 5, 1024)); break;
                case "deadline": verdict = deadline(kv, arg(args, 3, 3)); break;
                case "abandon": verdict = abandon(kv, arg(args, 3, 3), arg(args, 4, 6)); break;
                case "threads": verdict = threads(kv, arg(args, 3, 8), arg(args, 4, 32), arg(args, 5, 8)); break;
                case "ddl": verdict = ddl(kv, arg(args, 3, 8), arg(args, 4, 10)); break;
                case "http-rule": verdict = httpRule(kv); break;
                case "sql-log": verdict = sqlLog(kv); break;
                default: throw new IllegalArgumentException("unknown mode " + mode);
            }
        } catch (Throwable t) {
            verdict = "NO_DATA";
            kv.append("exception=").append(t.getClass().getSimpleName())
              .append(":").append(String.valueOf(t.getMessage()).replace(' ', '_'));
            t.printStackTrace(System.out);
        }
        System.out.println("RESULT tag=" + tag + " " + kv + " verdict=" + verdict);
        System.out.flush();
        Runtime.getRuntime().halt("PASS".equals(verdict) ? 0 : 1);
    }

    static int arg(String[] a, int i, int def) {
        return a.length > i ? Integer.parseInt(a[i]) : def;
    }

    // ---------------------------------------------------------------- 连接与执行

    static String url(String port, String schema) {
        return "jdbc:mysql://" + host + ":" + port + "/" + schema
                + "?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=Asia/Shanghai&connectTimeout=15000&socketTimeout=180000";
    }

    static Connection conn(String port, String schema) throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", user);
        p.setProperty("password", pass);
        return DriverManager.getConnection(url(port, schema), p);
    }

    static Connection front() throws SQLException { return conn(frontPort, "mysql"); }
    static Connection front(String schema) throws SQLException { return conn(frontPort, schema); }
    static Connection backA() throws SQLException { return conn(backPortA, "mysql"); }

    /** hint 与 DDL 一律 execute()，把结果集与更新数都消费掉，避免 Connector/J 误判。 */
    static void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            boolean hasRs = st.execute(sql);
            while (true) {
                if (hasRs) {
                    try (ResultSet rs = st.getResultSet()) { while (rs.next()) { /* drain */ } }
                } else if (st.getUpdateCount() < 0) {
                    break;
                }
                hasRs = st.getMoreResults();
                if (!hasRs && st.getUpdateCount() < 0) break;
            }
        }
    }

    static List<Map<String, Object>> query(Connection c, String sql) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Statement st = c.createStatement()) {
            boolean hasRs = st.execute(sql);
            if (!hasRs) return out;
            try (ResultSet rs = st.getResultSet()) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                    out.add(row);
                }
            }
        }
        return out;
    }

    static long useCon(Connection c, String dsName) throws SQLException {
        for (Map<String, Object> r : query(c, "/*+ mycat:showDataSources{} */")) {
            if (dsName.equalsIgnoreCase(String.valueOf(r.get("NAME")))) {
                Object v = r.get("USE_CON");
                return v instanceof Number ? ((Number) v).longValue() : -1;
            }
        }
        return -1;
    }

    static boolean is3007(SQLException e) {
        String m = String.valueOf(e.getMessage());
        return m.contains("MYCAT-3007") || m.contains("ERR_IO_CONCURRENCY_LIMIT");
    }

    static boolean is3013(SQLException e) {
        String m = String.valueOf(e.getMessage());
        return e.getErrorCode() == 3013
                || m.contains("MYCAT-3013")
                || m.contains("ERR_SQL_DISTRIBUTED_QUERY_TIMEOUT")
                || m.contains("Query_exceeded_statement_deadline");
    }

    // ---------------------------------------------------------------- setup

    static void setup() throws Exception {
        try (Connection f = front()) {
            exec(f, RESET_CONFIG);

            DatasourceConfig ds = CreateDataSourceHint.createConfig(DS, user, pass,
                    "jdbc:mysql://" + host + ":" + backPortA + "/mysql?useUnicode=true&characterEncoding=utf8"
                            + "&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai");
            ds.setMaxCon(1000);
            exec(f, CreateDataSourceHint.create(ds));
            exec(f, CreateClusterHint.create(CLUSTER, Arrays.asList(DS), Collections.emptyList()));

            // 短 deadline 数据源：L6 用 1500ms 界住卡死查询，好观察许可回收。
            DatasourceConfig shortDs = CreateDataSourceHint.createConfig(SHORT_DS, user, pass,
                    "jdbc:mysql://" + host + ":" + backPortA + "/mysql?useUnicode=true&characterEncoding=utf8"
                            + "&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai");
            shortDs.setMaxCon(1000);
            shortDs.setStatementTimeout(1500L);
            exec(f, CreateDataSourceHint.create(shortDs));
            exec(f, CreateClusterHint.create(SHORT_CLUSTER, Arrays.asList(SHORT_DS), Collections.emptyList()));

            exec(f, CreateSchemaHint.create(SCHEMA, CLUSTER));
            exec(f, CreateSchemaHint.create(SHORT_SCHEMA, SHORT_CLUSTER));
        }

        // 后端物理库与序列基座（沿用 AddSequenceTest 已验证的 MYCAT_SEQUENCE + mycat_seq_nextval 配方，
        // 该函数内含 GET_LOCK 与 SELECT ... FOR UPDATE ⇒ 同时覆盖 R3 行锁语义）。
        try (Connection b = backA(); Statement st = b.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS " + SCHEMA);
            st.execute("CREATE DATABASE IF NOT EXISTS " + SHORT_SCHEMA);
            st.execute("USE " + SCHEMA);
            st.execute("DROP TABLE IF EXISTS MYCAT_SEQUENCE");
            st.execute("CREATE TABLE MYCAT_SEQUENCE (NAME VARCHAR(64) NOT NULL, current_value BIGINT NOT NULL,"
                    + " increment INT NOT NULL DEFAULT 1, PRIMARY KEY (NAME)) ENGINE=INNODB");
            st.execute("DROP FUNCTION IF EXISTS mycat_seq_nextval");
            st.execute("CREATE FUNCTION mycat_seq_nextval(seq_name VARCHAR(64)) RETURNS VARCHAR(64) CHARSET latin1\n"
                    + "DETERMINISTIC\n"
                    + "BEGIN\n"
                    + "  DECLARE retval VARCHAR(64);\n"
                    + "  DECLARE val BIGINT;\n"
                    + "  DECLARE inc INT;\n"
                    + "  DECLARE seq_lock INT;\n"
                    + "  SET val = -1;\n"
                    + "  SET inc = 0;\n"
                    + "  SET seq_lock = -1;\n"
                    + "  SELECT GET_LOCK(seq_name, 15) INTO seq_lock;\n"
                    + "  IF seq_lock = 1 THEN\n"
                    + "    SELECT current_value + increment, increment INTO val, inc FROM MYCAT_SEQUENCE WHERE NAME = seq_name FOR UPDATE;\n"
                    + "    IF val != -1 THEN\n"
                    + "      UPDATE MYCAT_SEQUENCE SET current_value = val WHERE NAME = seq_name;\n"
                    + "    END IF;\n"
                    + "    SELECT RELEASE_LOCK(seq_name) INTO seq_lock;\n"
                    + "  END IF;\n"
                    + "  SELECT CONCAT(CAST((val - inc + 1) AS CHAR), ',', CAST(inc AS CHAR)) INTO retval;\n"
                    + "  RETURN retval;\n"
                    + "END");
            st.execute("INSERT INTO MYCAT_SEQUENCE (name, current_value, increment) VALUES ('" + SEQ_NAME + "', 0, 1)");
        }

        try (Connection f = front()) {
            exec(f, "USE " + SCHEMA);
            exec(f, "DROP TABLE IF EXISTS " + SCHEMA + "." + TABLE);
            exec(f, TABLE_DDL);
            exec(f, SET_SEQUENCE);
            exec(f, "DELETE FROM " + SCHEMA + "." + TABLE);
            // 冒烟：取号真前进，否则后面所有 seq 断言都是空转。
            exec(f, "INSERT INTO " + SCHEMA + "." + TABLE + " (user_id) VALUES ('smoke')");
            List<Map<String, Object>> id = query(f, "SELECT LAST_INSERT_ID()");
            if (id.isEmpty()) throw new IllegalStateException("LAST_INSERT_ID 无结果");
            exec(f, "CREATE TABLE IF NOT EXISTS " + SHORT_SCHEMA + ".stuck_t (id int primary key)");
            exec(f, "INSERT IGNORE INTO " + SHORT_SCHEMA + ".stuck_t (id) VALUES (1)");
            System.out.println("SETUP smokeLastInsertId=" + id.get(0).values());
        }
    }

    // ---------------------------------------------------------------- load / L9 反地雷

    static String load(StringBuilder kv, int concurrency, int iterations) throws Exception {
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger err = new AtomicInteger();
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        AtomicLong wall = new AtomicLong();
        CountDownLatch done = new CountDownLatch(concurrency);
        long t0 = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        for (int i = 0; i < concurrency; i++) {
            final int idx = i;
            pool.submit(() -> {
                try (Connection c = front(SCHEMA)) {
                    for (int k = 0; k < iterations; k++) {
                        int cur = inside.incrementAndGet();
                        maxInside.accumulateAndGet(cur, Math::max);
                        try {
                            switch ((idx + k) % 4) {
                                case 0: query(c, "SELECT id, user_id FROM " + TABLE + " WHERE id = " + (k + 1)); break;
                                case 1: exec(c, "INSERT INTO " + TABLE + " (user_id, fee) VALUES ('u" + idx + "_" + k + "', 10)"); break;
                                case 2: exec(c, "UPDATE " + TABLE + " SET fee = fee + 1 WHERE user_id = 'u" + idx + "_" + k + "'"); break;
                                default: exec(c, "DELETE FROM " + TABLE + " WHERE user_id = 'u" + idx + "_" + k + "'"); break;
                            }
                            ok.incrementAndGet();
                        } catch (SQLException e) {
                            err.incrementAndGet();
                        } finally {
                            inside.decrementAndGet();
                        }
                    }
                } catch (SQLException e) {
                    err.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        boolean finished = done.await(180, TimeUnit.SECONDS);
        wall.set((System.nanoTime() - t0) / 1_000_000);
        pool.shutdownNow();
        kv.append("C=").append(concurrency).append(" iter=").append(iterations)
          .append(" finished=").append(finished)
          .append(" ops=").append(ok.get()).append(" errors=").append(err.get())
          .append(" wallMs=").append(wall.get())
          .append(" maxClientInFlight=").append(maxInside.get());
        // L9 判据：客户端侧真实重叠 > 1，**且负载真的成功**。只看 maxInside 会被空转骗过——
        // 第三跑里表还没建起来，640 条全在报错，32 条连接照样"并发失败"，maxInside=32 判了 PASS。
        // 并发地失败不是反串行化的证据，故必须 ok>0 且 err==0。
        return finished && maxInside.get() > 1 && ok.get() > 0 && err.get() == 0 ? "PASS" : "FAIL";
    }

    // ---------------------------------------------------------------- saturate / L3

    /** 占满许可的长查询腿。返回释放闸与已在飞计数。 */
    static Sleeper[] startSleepers(int n, int sleepSeconds, String schema) throws Exception {
        return startSleepers(n, sleepSeconds, schema, false);
    }

    static Sleeper[] startSleepers(int n, int sleepSeconds, String schema, boolean backend) throws Exception {
        Sleeper[] s = new Sleeper[n];
        try (Connection observer = front()) {
            for (int i = 0; i < n; i++) {
                s[i] = new Sleeper(schema, sleepSeconds, backend);
                s[i].start();
            }
            for (Sleeper x : s) {
                if (!x.inFlight.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("sleeper 未在 30s 内进入在飞状态");
                }
            }
            if (backend) {
                String ds = SHORT_SCHEMA.equals(schema) ? SHORT_DS : DS;
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (System.nanoTime() < end) {
                    boolean allBorrowed = true;
                    for (Sleeper x : s) {
                        if (x.finished.getCount() == 0 || x.error.get() != null) allBorrowed = false;
                    }
                    if (allBorrowed && useCon(observer, ds) >= n) return s;
                    Thread.sleep(50);
                }
                throw new IllegalStateException("sleeper 未在 30s 内全部借到后端连接: "
                        + sleeperDiagnostics(s, observer, ds));
            }
            return s;
        } catch (Throwable t) {
            for (Sleeper x : s) {
                if (x != null) x.close();
            }
            throw t;
        }
    }

    static String sleeperDiagnostics(Sleeper[] sleepers, Connection observer, String ds) {
        StringBuilder out = new StringBuilder("useCon=");
        try {
            out.append(useCon(observer, ds));
        } catch (SQLException e) {
            out.append(e.getMessage());
        }
        for (int i = 0; i < sleepers.length; i++) {
            Sleeper s = sleepers[i];
            out.append(" sleeper").append(i).append("=finished:")
              .append(s.finished.getCount() == 0).append(",error:")
              .append(s.error.get() == null ? "none" : s.error.get().getMessage().replace(' ', '_'));
        }
        return out.toString();
    }

    static final class Sleeper implements Runnable {
        final Connection c;
        final int seconds;
        final String sql;
        final CountDownLatch inFlight = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicReference<SQLException> error = new AtomicReference<>();
        volatile Thread thread;

        Sleeper(String schema, int seconds, boolean backend) throws SQLException {
            this.c = front(schema);
            this.seconds = seconds;
            String querySchema = SCHEMA.equals(schema) ? SCHEMA : SHORT_SCHEMA;
            String queryTable = SCHEMA.equals(querySchema) ? TABLE : "stuck_t";
            this.sql = backend
                    ? "SELECT id, SLEEP(" + seconds + ") FROM " + querySchema + "." + queryTable + " LIMIT 1"
                    : "SELECT SLEEP(" + seconds + ")";
        }

        void start() {
            thread = new Thread(this, "sleeper-" + seconds);
            thread.setDaemon(true);
            thread.start();
        }

        @Override public void run() {
            try (Statement st = c.createStatement()) {
                inFlight.countDown();
                st.execute(sql);
            } catch (SQLException e) {
                error.set(e);
            } finally {
                finished.countDown();
            }
        }

        void close() {
            try { c.close(); } catch (SQLException ignored) { }
        }
    }

    static String saturate(StringBuilder kv, int permits, int sleepSeconds) throws Exception {
        try (Connection probe = front(SCHEMA)) {
            Sleeper[] s = startSleepers(permits, sleepSeconds, SCHEMA, true);
            try {
                long t0 = System.nanoTime();
                String code = "NONE";
                long waitMs = -1;
                boolean connAlive = false;
                try {
                    query(probe, "SELECT 1");
                    code = "UNEXPECTED_SUCCESS";
                } catch (SQLException e) {
                    waitMs = (System.nanoTime() - t0) / 1_000_000;
                    code = is3007(e) ? "MYCAT-3007" : ("OTHER:" + e.getErrorCode());
                }
                // 拒绝不得断连：同一连接在许可释放后必须还能用。
                for (Sleeper x : s) x.finished.await(60, TimeUnit.SECONDS);
                try {
                    query(probe, "SELECT 1");
                    connAlive = true;
                } catch (SQLException e) {
                    kv.append(" afterErr=").append(e.getMessage().replace(' ', '_'));
                }
                kv.append("permits=").append(permits).append(" rejectCode=").append(code)
                  .append(" rejectWaitMs=").append(waitMs).append(" connAliveAfterRelease=").append(connAlive);
                return "MYCAT-3007".equals(code) && connAlive ? "PASS" : "FAIL";
            } finally {
                for (Sleeper x : s) x.close();
            }
        }
    }

    // ---------------------------------------------------------------- ops / L8

    static String ops(StringBuilder kv, int permits, int sleepSeconds) throws Exception {
        try (Connection c = front()) {
            Sleeper[] s = startSleepers(permits, sleepSeconds, SCHEMA, true);
            try {
                // 豁免生效：饱和中控制面仍被受理。
                long t0 = System.nanoTime();
                List<Map<String, Object>> rows = query(c, ShowThreadInfoHint.create());
                long opsMs = (System.nanoTime() - t0) / 1_000_000;
                int vt = 0;
                int withStack = 0;
                long ioVtId = -1;
                for (Map<String, Object> r : rows) {
                    String name = String.valueOf(r.get("NAME"));
                    if (name.startsWith("mycat-io-")) {
                        vt++;
                        if (ioVtId < 0) ioVtId = Long.parseLong(String.valueOf(r.get("ID")));
                    }
                    String stack = String.valueOf(r.get("STACKTRACE"));
                    if (stack != null && !stack.isEmpty() && !"null".equals(stack)) withStack++;
                }
                kv.append("threadRows=").append(rows.size()).append(" ioVtRows=").append(vt)
                  .append(" nonEmptyStack=").append(withStack).append(" opsLatencyMs=").append(opsMs);
                if (vt == 0 || ioVtId < 0) {
                    kv.append(" reason=showThreadInfo_未列出_IO_VT");
                    return "FAIL";
                }
                // affectedRows 才是判据：HintHandler 无论命中与否都 sendOk()，只看 OK 包会假绿。
                int killAffected = affectedRows(c, KillThreadHint.create(ioVtId));
                long otherId = -1;
                for (Map<String, Object> r : rows) {
                    long id = Long.parseLong(String.valueOf(r.get("ID")));
                    if (String.valueOf(r.get("NAME")).startsWith("mycat-io-") && id != ioVtId) { otherId = id; break; }
                }
                int interruptAffected = otherId > 0 ? affectedRows(c, InterruptThreadHint.create(otherId)) : -1;
                int bogusAffected = affectedRows(c, KillThreadHint.create(999999999L));
                kv.append(" killAffected=").append(killAffected)
                  .append(" interruptAffected=").append(interruptAffected)
                  .append(" bogusKillAffected=").append(bogusAffected);
                boolean pass = killAffected == 1 && interruptAffected == 1 && bogusAffected == 0;
                return pass ? "PASS" : "FAIL";
            } finally {
                for (Sleeper x : s) x.close();
            }
        }
    }

    static int affectedRows(Connection c, String hint) throws SQLException {
        try (Statement st = c.createStatement()) {
            boolean hasRs = st.execute(hint);
            if (hasRs) {
                try (ResultSet rs = st.getResultSet()) { while (rs.next()) { /* drain */ } }
                return -1;
            }
            return st.getUpdateCount();
        }
    }

    // ---------------------------------------------------------------- seq / L5

    static String seq(StringBuilder kv, int concurrency, int hardTimeoutSeconds, int permits) throws Exception {
        try (Connection c = front(SCHEMA)) {
            exec(c, "DELETE FROM " + TABLE);
        }
        AtomicLong maxId = new AtomicLong();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger err3007 = new AtomicInteger();
        AtomicInteger errOther = new AtomicInteger();
        AtomicReference<String> firstErr = new AtomicReference<>("");
        AtomicReference<String> firstOtherErr = new AtomicReference<>("");
        CountDownLatch done = new CountDownLatch(concurrency);
        int per = 10;
        long t0 = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        for (int i = 0; i < concurrency; i++) {
            final int idx = i;
            pool.submit(() -> {
                try (Connection c = front(SCHEMA)) {
                    for (int k = 0; k < per; k++) {
                        try {
                            exec(c, "INSERT INTO " + TABLE + " (user_id) VALUES ('seq" + idx + "_" + k + "')");
                            List<Map<String, Object>> r = query(c, "SELECT LAST_INSERT_ID()");
                            if (!r.isEmpty()) {
                                Object v = r.get(0).values().iterator().next();
                                if (v instanceof Number) maxId.accumulateAndGet(((Number) v).longValue(), Math::max);
                            }
                            ok.incrementAndGet();
                        } catch (SQLException e) {
                            if (is3007(e)) err3007.incrementAndGet(); else errOther.incrementAndGet();
                            firstErr.compareAndSet("", e.getMessage());
                        }
                    }
                } catch (SQLException e) {
                    if (is3007(e)) {
                        err3007.incrementAndGet();
                    } else {
                        errOther.incrementAndGet();
                        firstOtherErr.compareAndSet("", e.getMessage());
                    }
                    firstErr.compareAndSet("", e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }
        // 硬超时：有界准入闸最典型的翻车点就是把取号腿饿死。绝不无限等。
        boolean finished = done.await(hardTimeoutSeconds, TimeUnit.SECONDS);
        long wallMs = (System.nanoTime() - t0) / 1_000_000;
        pool.shutdownNow();
        long rows;
        try (Connection c = front(SCHEMA)) {
            List<Map<String, Object>> r = query(c, "SELECT COUNT(*) AS n FROM " + TABLE);
            rows = r.isEmpty() ? -1 : ((Number) r.get(0).get("n")).longValue();
        }
        kv.append("C=").append(concurrency).append(" permits=").append(permits)
          .append(" perThread=").append(per)
          .append(" hardTimeoutS=").append(hardTimeoutSeconds).append(" finished=").append(finished)
          .append(" wallMs=").append(wallMs).append(" ok=").append(ok.get())
          .append(" err3007=").append(err3007.get()).append(" errOther=").append(errOther.get())
          .append(" maxId=").append(maxId.get()).append(" tableRows=").append(rows)
          .append(" firstErr=").append(firstErr.get().replace(' ', '_'))
          .append(" firstOtherErr=").append(firstOtherErr.get().replace(' ', '_'));
        // 判据分两档：
        //  C <= permits（全部可准入）⇒ 必须零错且全成功。若取号腿被误接闸，这里会挂到硬超时 ⇒ finished=false。
        //  C >  permits（真饱和）  ⇒ 只许出现干净的 3007 拒绝，不许挂住、不许其他错，且已准入的取号持续前进。
        if (!finished || errOther.get() != 0 || ok.get() == 0 || maxId.get() < ok.get()) return "FAIL";
        if (concurrency <= permits) {
            return err3007.get() == 0 && ok.get() == concurrency * per ? "PASS" : "FAIL";
        }
        return "PASS";
    }

    // ---------------------------------------------------------------- deadline / L6

    static String deadline(StringBuilder kv, int permits) throws Exception {
        try (Connection observer = front(SHORT_SCHEMA)) {
            Sleeper[] s = startSleepers(permits, 30, SHORT_SCHEMA, true);
            try {
                int bounded = 0;
                int unexpected = 0;
                String unexpectedFirst = "none";
                long firstMs = -1;
                long t0 = System.nanoTime();
                for (Sleeper x : s) {
                    if (x.finished.await(30, TimeUnit.SECONDS)) {
                        SQLException e = x.error.get();
                        if (e != null && is3013(e)) {
                            bounded++;
                            if (firstMs < 0) firstMs = (System.nanoTime() - t0) / 1_000_000;
                        } else {
                            // 聚合成单键：原先逐条 append 会产出重复键，且末条无尾空格 ⇒ 与后续 permits= 粘连成 nonepermits=3。
                            unexpected++;
                            if (unexpectedFirst.equals("none")) {
                                unexpectedFirst = e == null ? "null-error" : e.getMessage().replace(' ', '_');
                            }
                        }
                    }
                }
                kv.append(" unexpected=").append(unexpected).append(" unexpectedFirst=").append(unexpectedFirst);
                // 许可回收判据：deadline 界住客户端后，复用预建连接，允许服务端完成 promise 释放的短暂窗口。
                boolean admittedAfter = false;
                long admitMs = -1;
                String afterErr = "none";
                long t1 = System.nanoTime();
                for (int i = 0; i < 50; i++) {
                    try {
                        query(observer, "SELECT 1");
                        admittedAfter = true;
                        admitMs = (System.nanoTime() - t1) / 1_000_000;
                        break;
                    } catch (SQLException e) {
                        afterErr = e.getMessage().replace(' ', '_');
                        Thread.sleep(100);
                    }
                }
                kv.append(" afterErr=").append(afterErr);
                kv.append(" permits=").append(permits).append(" bounded3013=").append(bounded)
                  .append(" firstBoundMs=").append(firstMs)
                  .append(" admittedAfterDeadline=").append(admittedAfter).append(" admitMs=").append(admitMs);
                return bounded == permits && admittedAfter ? "PASS" : "FAIL";
            } finally {
                for (Sleeper x : s) x.close();
            }
        }
    }

    // ---------------------------------------------------------------- abandon / L4

    static String abandon(StringBuilder kv, int permits, int sleepSeconds) throws Exception {
        try (Connection observer = front()) {
            long before = useCon(observer, DS);
            Sleeper[] s = startSleepers(permits, sleepSeconds, SCHEMA, true);
            long peak = before;
            long peakWaitMs = -1;
            long after = -1;
            boolean rose = false;
            boolean fell = false;
            try {
                long peakStart = System.nanoTime();
                for (int i = 0; i < 100; i++) {
                    peak = useCon(observer, DS);
                    if (peak > before) {
                        rose = true;
                        peakWaitMs = (System.nanoTime() - peakStart) / 1_000_000;
                        break;
                    }
                    Thread.sleep(100);
                }
                // 主动断连：客户端 socket 直接关掉，不等查询结束。
                for (Sleeper x : s) x.close();
                for (int i = 0; i < 40; i++) {
                    after = useCon(observer, DS);
                    if (after <= before) { fell = true; break; }
                    Thread.sleep(500);
                }
            } finally {
                for (Sleeper x : s) x.close();
            }
            kv.append("useConBefore=").append(before).append(" useConPeak=").append(peak)
              .append(" peakWaitMs=").append(peakWaitMs).append(" useConAfter=").append(after)
              .append(" rose=").append(rose).append(" fellBack=").append(fell);
            return rose && fell ? "PASS" : "FAIL";
        }
    }

    // ---------------------------------------------------------------- threads / L2

    static int ioVtCount(List<Map<String, Object>> rows) {
        int vt = 0;
        for (Map<String, Object> row : rows) {
            if (String.valueOf(row.get("NAME")).startsWith("mycat-io-")) vt++;
        }
        return vt;
    }

    static final class ThreadSample {
        final boolean finished;
        final long ops;
        final long errors;
        final int peakVt;
        final int peakPlatform;
        final int samples;
        final long wallMs;
        final Set<String> addedPlatformNames;

        ThreadSample(boolean finished, long ops, long errors, int peakVt, int peakPlatform,
                     int samples, long wallMs, Set<String> addedPlatformNames) {
            this.finished = finished;
            this.ops = ops;
            this.errors = errors;
            this.peakVt = peakVt;
            this.peakPlatform = peakPlatform;
            this.samples = samples;
            this.wallMs = wallMs;
            this.addedPlatformNames = addedPlatformNames;
        }
    }

    static ThreadSample measureThreadPhase(int concurrency, int seconds, int baselineVt,
                                            int baselinePlatform, Set<String> baselineNames) throws Exception {
        AtomicInteger maxRows = new AtomicInteger();
        AtomicInteger maxVt = new AtomicInteger(baselineVt);
        AtomicInteger maxPlatform = new AtomicInteger(baselinePlatform);
        AtomicInteger samples = new AtomicInteger();
        Set<String> addedPlatformNames = java.util.concurrent.ConcurrentHashMap.newKeySet();
        CountDownLatch stop = new CountDownLatch(1);
        Thread sampler = new Thread(() -> {
            try (Connection c = front()) {
                while (stop.getCount() > 0) {
                    List<Map<String, Object>> rows = query(c, ShowThreadInfoHint.create());
                    int vt = ioVtCount(rows);
                    for (Map<String, Object> row : rows) {
                        String name = String.valueOf(row.get("NAME"));
                        if (!name.startsWith("mycat-io-") && !baselineNames.contains(name)) {
                            addedPlatformNames.add(name);
                        }
                    }
                    samples.incrementAndGet();
                    maxRows.accumulateAndGet(rows.size(), Math::max);
                    maxVt.accumulateAndGet(vt, Math::max);
                    maxPlatform.accumulateAndGet(rows.size() - vt, Math::max);
                    Thread.sleep(200);
                }
            } catch (Exception ignored) {
            }
        }, "thread-sampler-" + concurrency);
        sampler.setDaemon(true);
        sampler.start();

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger err = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(concurrency);
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        long t0 = System.nanoTime();
        for (int i = 0; i < concurrency; i++) {
            pool.submit(() -> {
                try (Connection c = front(SCHEMA)) {
                    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
                    while (System.nanoTime() < end) {
                        try {
                            query(c, "SELECT COUNT(*) FROM " + TABLE);
                            ok.incrementAndGet();
                        } catch (SQLException e) {
                            err.incrementAndGet();
                        }
                    }
                } catch (SQLException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        boolean finished = done.await(seconds + 60L, TimeUnit.SECONDS);
        stop.countDown();
        sampler.join(5000);
        pool.shutdownNow();
        return new ThreadSample(finished, ok.get(), err.get(), maxVt.get(), maxPlatform.get(),
                samples.get(), (System.nanoTime() - t0) / 1_000_000, addedPlatformNames);
    }

    static String threads(StringBuilder kv, int lowConcurrency, int highConcurrency, int seconds) throws Exception {
        int baselineVt = 0;
        int baselinePlatform = 0;
        Set<String> baselineNames = new java.util.HashSet<>();
        try (Connection baseline = front()) {
            for (int i = 0; i < 10; i++) {
                List<Map<String, Object>> rows = query(baseline, ShowThreadInfoHint.create());
                baselineVt = ioVtCount(rows);
                baselinePlatform = rows.size() - baselineVt;
                baselineNames.clear();
                for (Map<String, Object> row : rows) baselineNames.add(String.valueOf(row.get("NAME")));
                if (i < 9) Thread.sleep(200);
            }
        }

        ThreadSample low = measureThreadPhase(lowConcurrency, seconds, baselineVt, baselinePlatform, baselineNames);
        Thread.sleep(5000);
        ThreadSample high = measureThreadPhase(highConcurrency, seconds, baselineVt, baselinePlatform, baselineNames);
        int lowGrowth = low.peakPlatform - baselinePlatform;
        int highGrowth = high.peakPlatform - baselinePlatform;
        boolean noPlatformCarrier = low.addedPlatformNames.stream().noneMatch(n -> n.startsWith("mycat-io-"))
                && high.addedPlatformNames.stream().noneMatch(n -> n.startsWith("mycat-io-"));
        kv.append("lowC=").append(lowConcurrency).append(" highC=").append(highConcurrency)
          .append(" loadSeconds=").append(seconds)
          .append(" lowFinished=").append(low.finished).append(" lowOps=").append(low.ops).append(" lowErrors=").append(low.errors)
          .append(" highFinished=").append(high.finished).append(" highOps=").append(high.ops).append(" highErrors=").append(high.errors)
          .append(" baselineIoVt=").append(baselineVt).append(" baselinePlatform=").append(baselinePlatform)
          .append(" lowPeakIoVt=").append(low.peakVt).append(" highPeakIoVt=").append(high.peakVt)
          .append(" lowPeakPlatform=").append(low.peakPlatform).append(" highPeakPlatform=").append(high.peakPlatform)
          .append(" lowPlatformGrowth=").append(lowGrowth).append(" highPlatformGrowth=").append(highGrowth)
          .append(" allowedExtraPlatformGrowth=2").append(" noPlatformCarrier=").append(noPlatformCarrier)
          .append(" lowSamples=").append(low.samples).append(" highSamples=").append(high.samples)
          .append(" lowAddedPlatformNames=").append(String.join(",", low.addedPlatformNames).replace(' ', '_'))
          .append(" highAddedPlatformNames=").append(String.join(",", high.addedPlatformNames).replace(' ', '_'))
          .append(" lowWallMs=").append(low.wallMs).append(" highWallMs=").append(high.wallMs);
        return lowConcurrency > 0 && highConcurrency > lowConcurrency
                && low.finished && high.finished && low.ops > 0 && high.ops > 0
                && low.errors == 0 && high.errors == 0 && low.samples > 0 && high.samples > 0
                && high.peakVt > low.peakVt && noPlatformCarrier
                && highGrowth <= lowGrowth + 2 ? "PASS" : "FAIL";
    }

    // ---------------------------------------------------------------- ddl / R2

    static String ddl(StringBuilder kv, int concurrency, int rounds) throws Exception {
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger err = new AtomicInteger();
        AtomicReference<String> firstErr = new AtomicReference<>("");
        CountDownLatch done = new CountDownLatch(concurrency);
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        for (int i = 0; i < concurrency; i++) {
            final int idx = i;
            pool.submit(() -> {
                try (Connection c = front(SCHEMA)) {
                    for (int k = 0; k < rounds; k++) {
                        String t = "ddl_t" + idx + "_" + k;
                        try {
                            exec(c, "CREATE TABLE " + SCHEMA + "." + t + " (id int primary key, v varchar(32))"
                                    + " dbpartition by mod_hash(id) dbpartitions 2");
                            exec(c, "INSERT INTO " + SCHEMA + "." + t + " (id, v) VALUES (1, 'x')");
                            query(c, "SELECT id, v FROM " + SCHEMA + "." + t);
                            exec(c, "DROP TABLE " + SCHEMA + "." + t);
                            ok.incrementAndGet();
                        } catch (SQLException e) {
                            err.incrementAndGet();
                            firstErr.compareAndSet("", e.getMessage());
                        }
                    }
                } catch (SQLException e) {
                    err.incrementAndGet();
                    firstErr.compareAndSet("", e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }
        boolean finished = done.await(180, TimeUnit.SECONDS);
        pool.shutdownNow();
        kv.append("C=").append(concurrency).append(" rounds=").append(rounds)
          .append(" finished=").append(finished).append(" ok=").append(ok.get()).append(" errors=").append(err.get())
          .append(" firstErr=").append(firstErr.get().replace(' ', '_'));
        // R2 判据：ok>0 防「零轮次零错误」的空断言——err==0 在什么都没跑成时同样成立。
        return finished && ok.get() > 0 && err.get() == 0 ? "PASS" : "FAIL";
    }

    // ---------------------------------------------------------------- http custom-rule / R4

    static String httpRule(StringBuilder kv) throws Exception {
        String table = "r4_http";
        try (Connection b = backA()) {
            exec(b, "CREATE DATABASE IF NOT EXISTS " + SCHEMA);
            exec(b, "DROP TABLE IF EXISTS " + SCHEMA + "." + table);
            exec(b, "CREATE TABLE " + SCHEMA + "." + table + " (id int primary key, v int)");
            exec(b, "INSERT INTO " + SCHEMA + "." + table + " (id, v) VALUES (1, 7)");
        }

        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> stubError = new AtomicReference<>("");
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread stub = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept();
                         BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                         OutputStream out = socket.getOutputStream()) {
                        String line;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) {
                            // 只消费请求头；FormBody 随后由连接关闭处理，探针只验证路由腿确实到达 HTTP。
                        }
                        requests.incrementAndGet();
                        Thread.sleep(250);
                        String json = "[{\"targetName\":\"" + CLUSTER + "\",\"schema\":\"" + SCHEMA
                                + "\",\"table\":\"" + table + "\",\"dbIndex\":0,\"tableIndex\":0,\"index\":0}]";
                        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
                        String headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                                + payload.length + "\r\nConnection: close\r\n\r\n";
                        out.write(headers.getBytes(StandardCharsets.US_ASCII));
                        out.write(payload);
                        out.flush();
                    } catch (Exception e) {
                        if (!server.isClosed()) stubError.compareAndSet("", e.getClass().getSimpleName() + ":" + e.getMessage());
                    }
                }
            }, "r4-http-stub");
            stub.setDaemon(true);
            stub.start();

            long t0 = System.nanoTime();
            List<Map<String, Object>> rows;
            try (Connection f = front(SCHEMA)) {
                Map<String, Object> properties = new LinkedHashMap<>();
                properties.put("name", "r4_http");
                properties.put("shardingDbKeys", "");
                properties.put("shardingTableKeys", "id");
                properties.put("shardingTargetKeys", "");
                properties.put("allScanPartitionTimeout", 3600);
                properties.put("fetchTimeout", 5000);
                properties.put("routerServiceAddress", "http://127.0.0.1:" + server.getLocalPort() + "/routerserivce");
                ShardingFunction function = ShardingFunction.builder()
                        .clazz("io.mycat.router.custom.HttpCustomRuleFunction")
                        .properties(properties)
                        .build();
                ShardingBackEndTableInfoConfig partition = ShardingBackEndTableInfoConfig.builder()
                        .targetNames(CLUSTER)
                        .schemaNames(SCHEMA)
                        .tableNames(table)
                        .build();
                exec(f, CreateTableHint.createSharding(SCHEMA, table,
                        "CREATE TABLE " + table + " (id int primary key, v int)", partition, function));
                rows = query(f, "SELECT id, v FROM " + SCHEMA + "." + table + " WHERE id = 1");
            }
            long wallMs = (System.nanoTime() - t0) / 1_000_000;
            boolean routed = rows.size() == 1 && ((Number) rows.get(0).get("id")).intValue() == 1
                    && ((Number) rows.get(0).get("v")).intValue() == 7;
            kv.append("httpRequests=").append(requests.get())
              .append(" routedRows=").append(rows.size()).append(" wallMs=").append(wallMs)
              .append(" stubError=").append(stubError.get().replace(' ', '_'));
            return requests.get() > 0 && routed && stubError.get().isEmpty() ? "PASS" : "FAIL";
        }
    }

    // ---------------------------------------------------------------- sql log consumer / R5

    static String sqlLog(StringBuilder kv) throws Exception {
        String frontendTable = "r5_sql_log_" + System.currentTimeMillis();
        try (Connection f = front(SCHEMA)) {
            exec(f, CreateTableHint.createNormal(SCHEMA, frontendTable,
                    "CREATE TABLE " + frontendTable + " (id int primary key)", CLUSTER));
            exec(f, "INSERT INTO " + SCHEMA + "." + frontendTable + " (id) VALUES (1)");
        }
        try (Connection f = front(SCHEMA); Connection b = backA()) {
            ensureSqlLogTable(b);
            exec(b, "DELETE FROM mycat.sql_log");
            exec(f, "/*+ mycat:setSqlTimeFilter{value:0} */");
            exec(f, "SELECT id FROM " + frontendTable + " LIMIT 1");
            boolean warmed = waitForSqlLogRows(b, 1, 10);
            if (!warmed) {
                kv.append(" warmupRows=0");
                return "FAIL";
            }
            exec(b, "DELETE FROM mycat.sql_log");
            if (!awaitSqlLogIdle(f, b)) {
                kv.append(" warmupSqlLogIdle=false");
                return "FAIL";
            }
        }

        replaceDatasource(3);
        try {
            boolean maxConApplied = false;
            try (Connection f = front()) {
                for (int i = 0; i < 30; i++) {
                    if (maxCon(f, DS) == 3) { maxConApplied = true; break; }
                    Thread.sleep(200);
                }
            }
            if (!maxConApplied) {
                kv.append(" maxConApplied=false");
                return "FAIL";
            }

            try (Connection b = backA(); Connection f = front(SCHEMA)) {
                exec(b, "DELETE FROM mycat.sql_log");
                exec(f, "SELECT id FROM " + frontendTable + " LIMIT 1");
                if (!waitForSqlLogRows(b, 1, 10)) {
                    kv.append(" replacementWarmupRows=0");
                    return "FAIL";
                }
                exec(b, "DELETE FROM mycat.sql_log");
            }

            boolean blockedInsertObserved = false;
            boolean frontendCompleted = false;
            long frontendMs = -1;
            long loggedRows = -1;
            try (Connection locker = backA(); Connection observer = backA()) {
                exec(locker, "LOCK TABLES mycat.sql_log WRITE");
                try (Connection f = front(SCHEMA)) {
                    exec(f, "SELECT id FROM " + frontendTable + " LIMIT 1");
                    for (int i = 0; i < 50; i++) {
                        if (processListHasSqlLogInsert(observer)) { blockedInsertObserved = true; break; }
                        Thread.sleep(100);
                    }
                    long t0 = System.nanoTime();
                    query(f, "SELECT id FROM " + frontendTable + " LIMIT 1");
                    frontendCompleted = true;
                    frontendMs = (System.nanoTime() - t0) / 1_000_000;
                } finally {
                    exec(locker, "UNLOCK TABLES");
                }
                for (int i = 0; i < 50; i++) {
                    loggedRows = sqlLogCount(observer);
                    if (loggedRows > 0) break;
                    Thread.sleep(100);
                }
            }
            kv.append("maxCon=3 sessionHeld=1 blockedSqlLog=1 frontendQuery=1 singleShardTable=").append(frontendTable)
              .append(" blockedInsertObserved=").append(blockedInsertObserved)
              .append(" frontendCompleted=").append(frontendCompleted)
              .append(" frontendMs=").append(frontendMs)
              .append(" loggedRows=").append(loggedRows);
            return blockedInsertObserved && frontendCompleted && loggedRows > 0 ? "PASS" : "FAIL";
        } finally {
            try (Connection f = front(); Connection b = backA()) {
                awaitSqlLogIdle(f, b);
            } catch (SQLException ignored) {
            } finally {
                replaceDatasource(1000);
            }
        }
    }

    static boolean awaitSqlLogIdle(Connection f, Connection b) throws Exception {
        int quiet = 0;
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < end) {
            if (!processListHasSqlLogInsert(b) && useCon(f, "prototypeDs") == 0) {
                quiet++;
                if (quiet >= 3) return true;
            } else {
                quiet = 0;
            }
            Thread.sleep(200);
        }
        return false;
    }

    static void ensureSqlLogTable(Connection b) throws SQLException {
        exec(b, "CREATE DATABASE IF NOT EXISTS mycat");
        exec(b, "CREATE TABLE IF NOT EXISTS mycat.sql_log ("
                + "instanceId bigint, user varchar(64), connectionId bigint, ip varchar(22), port bigint,"
                + "traceId varchar(22) NOT NULL PRIMARY KEY, hash varchar(22), sqlType varchar(22),"
                + "`sql` longtext, transactionId varchar(22), sqlTime bigint, responseTime datetime,"
                + "affectRow int, result tinyint, externalMessage tinytext)");
    }

    static boolean waitForSqlLogRows(Connection b, long atLeast, int timeoutSeconds) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < end) {
            if (sqlLogCount(b) >= atLeast) return true;
            Thread.sleep(100);
        }
        return false;
    }

    static long sqlLogCount(Connection b) throws SQLException {
        List<Map<String, Object>> rows = query(b, "SELECT COUNT(*) AS n FROM mycat.sql_log");
        return rows.isEmpty() ? -1 : ((Number) rows.get(0).get("n")).longValue();
    }

    static boolean processListHasSqlLogInsert(Connection b) throws SQLException {
        for (Map<String, Object> row : query(b, "SHOW PROCESSLIST")) {
            for (Object value : row.values()) {
                if (value != null && String.valueOf(value).toLowerCase().contains("sql_log")) return true;
            }
        }
        return false;
    }

    static long maxCon(Connection f, String dsName) throws SQLException {
        for (Map<String, Object> row : query(f, "/*+ mycat:showDataSources{} */")) {
            if (dsName.equalsIgnoreCase(String.valueOf(row.get("NAME")))) {
                Object value = row.get("MAX_CON");
                return value instanceof Number ? ((Number) value).longValue() : -1;
            }
        }
        return -1;
    }

    static void replaceDatasource(int maxCon) throws SQLException {
        try (Connection f = front()) {
            DatasourceConfig ds = CreateDataSourceHint.createConfig(DS, user, pass,
                    "jdbc:mysql://" + host + ":" + backPortA + "/mysql?useUnicode=true&characterEncoding=utf8"
                            + "&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&autoReconnect=true");
            ds.setMaxCon(maxCon);
            ds.setMinCon(1);
            exec(f, CreateDataSourceHint.create(ds));
        }
    }
}