/**
 * Copyright [2021] [chen junwen]
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.mycat.xa;

import io.mycat.assemble.MycatTest;
import io.mycat.config.DatasourceConfig;
import io.mycat.hint.CreateDataSourceHint;
import io.mycat.hint.CreateSchemaHint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * I-DEBT-84 Phase2 阶段2（spec §8 R5）commit 腿 deadline 的<b>端到端活体取证</b>。
 *
 * <p>确定性单 DS 配方（承 ledger Task5 收敛裁定）：真代理 8066 + 真后端 m1-mysql-a:3306，
 * datasource {@code statementTimeout=3000ms}。客户端开事务 INSERT 成功（后端存活）⇒
 * {@code docker pause} 冻结后端（TCP 黑洞：socket 仍在、进程不响应）⇒ {@code commit;} 挂 ⇒
 * 3s 语句级 deadline 触发 {@code forceCloseInFlight} 力断在途物理连接、解困阻塞 worker ⇒
 * 客户端<b>有界</b>拿到 3013（ERR_SQL_DISTRIBUTED_QUERY_TIMEOUT）⇒ {@code docker unpause} ⇒
 * 新事务可提交、数据可见（代理恢复，无孤儿/池耗尽阻塞新工作）。
 *
 * <p>本探针补的唯一证据 = 真冻结 socket 被 force-close 解困 + 端到端客户端有界 3013 + 代理恢复
 * （Review Focus #5）。多 DS 决定点语义已由 {@code MixedPathCommitConsistencyTest}（真后端 JDBC 级
 * PROBE-A/B）+ {@code CommitDecidedFlagTest} 确定性钉死；retry 让位由 {@code RetryCommitYieldTest}；
 * deadline 接线/force-close 计数/文案由 {@code ReceiverCommitLegDeadlineTest}。把 docker pause 精确卡在
 * PREPARE↔XA COMMIT 边界经真代理属竞态、不可确定性复现，故活体只跑单 DS 端到端解困这一格。
 *
 * <p><b>内置负控</b>：commit 在独立线程跑、{@code Future.get(15s)} 有界等待——若 commit 腿未接 deadline
 * （去包裹变异），commit 永挂 ⇒ 15s {@code TimeoutException} ⇒ 本格红。socketTimeout=20s 仅作客户端兜底，
 * 远晚于 3s deadline，保证正常情况下是代理的 3013 先到。
 *
 * <p><b>前置</b>：8066 代理已起（{@code .superpowers/start-server.ps1}，用刚重打的 fat-jar）；
 * docker m1-mysql-a 存活。{@code @AfterAll} 无条件 {@code unpause}（绝不把后端留在冻结态）+ 逻辑/物理 drop + RESET_CONFIG。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CommitLegLiveFreezeTest implements MycatTest {

    private static final String SCHEMA = "freeze_db";
    private static final String DS = "freezeDs";
    private static final String BACKEND_CONTAINER = "m1-mysql-a";
    /** 代理侧语句级 deadline：3s 加速探针（生产默认 30s）。 */
    private static final long DEADLINE_MS = 3000L;
    /** 客户端有界等待上限：远大于 3s deadline、远小于 socketTimeout 兜底；无 deadline 则此处红。 */
    private static final long CLIENT_BOUND_MS = 15000L;

    /** 客户端连 8066，socketTimeout=20s 仅作兜底（正常情况下代理 3s deadline 的 3013 先到）。 */
    private Connection client() throws SQLException {
        return raw(DB_MYCAT + "&socketTimeout=20000&connectTimeout=10000");
    }

    /**
     * 裸 JDBC 连接：显式 Properties 传 user/password。MycatTest 的 URL 用 {@code username=} 查询参，
     * Connector/J 只认 {@code user}（不认 username），不显式传会回退 OS 用户名 → Access denied。
     * 与 {@code MixedPathCommitConsistencyTest.raw()} 同配方。
     */
    private static Connection raw(String url) throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", "root");
        p.setProperty("password", "123456");
        return DriverManager.getConnection(url, p);
    }

    @BeforeAll
    public void setUp() throws Exception {
        try (Connection setup = getMySQLConnection(DB_MYCAT)) {
            execute(setup, RESET_CONFIG);
            execute(setup, "drop database if exists " + SCHEMA);
            purgePhysical();
            DatasourceConfig cfg = CreateDataSourceHint.createConfig(DS, "root", "123456", DB1);
            cfg.setStatementTimeout(DEADLINE_MS);
            execute(setup, CreateDataSourceHint.create(cfg));
            // 物理库建在 freezeDs 的后端 DB1：createSchema hint 只注册逻辑库，不建物理库。
            try (Connection backend = raw(DB1)) {
                execute(backend, "create database if not exists " + SCHEMA);
            }
            // 逻辑库 targetName 绑 freezeDs ⇒ 非分片 freeze_t 经 putNormalTable 继承该 target，
            // 路由到 freezeDs（statementTimeout=3s）而非 prototypeDs（默认 30s）。不绑则 commit 腿
            // deadline=30s，探针 15s 界先超时假红（活体取证已坐实此路由）。
            execute(setup, CreateSchemaHint.create(SCHEMA, DS));
            execute(setup, "use " + SCHEMA);
            execute(setup, "create table freeze_t (`id` bigint NOT NULL, `v` int, PRIMARY KEY(`id`)) "
                    + "ENGINE=InnoDB DEFAULT CHARSET=utf8");
        }
    }

    @AfterAll
    public void tearDown() {
        // 绝不把后端留在冻结态：无论用例成败先解冻。
        safeUnpause();
        try (Connection setup = getMySQLConnection(DB_MYCAT)) {
            execute(setup, "drop database if exists " + SCHEMA);
            execute(setup, RESET_CONFIG);
        } catch (Throwable t) {
            System.out.println("[LIVE] teardown logical drop/reset best-effort failed: " + t);
        }
        try {
            purgePhysical();
        } catch (Throwable t) {
            System.out.println("[LIVE] teardown physical purge best-effort failed: " + t);
        }
    }

    /**
     * 单 DS commit 冻结：客户端有界 3013 ⇒ 解冻后代理恢复。
     */
    @Test
    public void singleDsCommitFreeze_clientBounded3013_thenProxyRecovers() throws Exception {
        // 1) 后端存活：开事务 INSERT 成功，证明网/路由/建表本身成立（此步若红，其余结论全废）。
        Connection client = client();
        try {
            execute(client, "use " + SCHEMA);
            client.setAutoCommit(false);
            execute(client, "insert into freeze_t(id,v) values(1,42)");

            // 2) 冻结后端 ⇒ commit 腿挂 ⇒ 3s deadline 力断 ⇒ 客户端有界 3013。
            docker("docker", "pause", BACKEND_CONTAINER);
            try {
                assertCommitBounded3013(client);
            } finally {
                safeUnpause();
            }
        } finally {
            closeQuietly(client);
        }

        // 3) 解冻后代理恢复：新事务可提交、数据可见（force-close 已解困 worker，无孤儿/池耗尽阻塞新工作）。
        awaitBackendReady();
        try (Connection rec = client()) {
            execute(rec, "use " + SCHEMA);
            rec.setAutoCommit(false);
            execute(rec, "insert into freeze_t(id,v) values(2,43)");
            rec.commit();
        }
        try (Connection v = client()) {
            execute(v, "use " + SCHEMA);
            List<Map<String, Object>> rows = executeQuery(v, "select v from freeze_t where id=2");
            assertEquals(1, rows.size(), "恢复后新事务数据应可见（代理未泄漏/未池耗尽）");
            assertEquals(43, ((Number) rows.get(0).get("v")).intValue(), "恢复后写入值保真");
        }
        System.out.println("[LIVE] proxy recovered: fresh transaction committed and visible after unpause");
    }

    /**
     * 在独立线程跑 {@code commit()}，{@code Future.get(15s)} 有界等待：
     * 正常 ⇒ 代理 3s deadline 的 3013 经 {@code ExecutionException(SQLException)} 回来；
     * 去包裹变异 ⇒ commit 永挂 ⇒ 15s {@code TimeoutException} ⇒ 红（内置负控）。
     */
    private void assertCommitBounded3013(Connection client) throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            long t0 = System.currentTimeMillis();
            Future<Void> f = ex.submit(() -> {
                client.commit();
                return null;
            });
            try {
                f.get(CLIENT_BOUND_MS, TimeUnit.MILLISECONDS);
                fail("commit 应被 deadline 力断并回 3013，却成功了（后端冻结中不应提交成功）");
            } catch (ExecutionException ee) {
                long elapsed = System.currentTimeMillis() - t0;
                Throwable cause = ee.getCause();
                assertTrue(cause instanceof SQLException, "客户端应拿 SQLException，实得 " + cause);
                assertEquals(3013, ((SQLException) cause).getErrorCode(),
                        "客户端应拿 3013 ERR_SQL_DISTRIBUTED_QUERY_TIMEOUT，实得 " + ((SQLException) cause).getErrorCode()
                                + " / " + cause.getMessage());
                assertTrue(elapsed < DEADLINE_MS + 5000L,
                        "commit 应被 ~3s 代理 deadline 界住（而非 20s socketTimeout 兜底），elapsed=" + elapsed + "ms");
                System.out.println("[LIVE] commit bounded 3013 in " + elapsed + "ms (deadline=" + DEADLINE_MS
                        + "ms), msg=" + cause.getMessage());
            } catch (TimeoutException te) {
                fail("commit 未在 " + CLIENT_BOUND_MS + "ms 内有界完成 —— commit 腿 deadline 接线缺失（负控红）");
            }
        } finally {
            ex.shutdownNow();
        }
    }

    /** 解冻后轮询后端就绪（docker unpause 即恢复进程，此处仅兜住极短就绪窗口），有界 15s。 */
    private void awaitBackendReady() throws Exception {
        long deadline = System.currentTimeMillis() + 15000L;
        SQLException last = null;
        while (System.currentTimeMillis() < deadline) {
            try (Connection c = raw(DB1)) {
                execute(c, "select 1");
                return;
            } catch (SQLException e) {
                last = e;
                Thread.sleep(300L);
            }
        }
        throw new IllegalStateException("解冻后后端 15s 内未就绪", last);
    }

    /** 物理 purge：后端同名物理库不随逻辑 drop 消失，按前缀清。每次开新连接，避免冻结期持有陈旧连接。 */
    private void purgePhysical() throws Exception {
        try (Connection backend = raw(DB1)) {
            List<Map<String, Object>> schemas = executeQuery(backend,
                    "select schema_name from information_schema.schemata where schema_name like '" + SCHEMA + "%'");
            for (Map<String, Object> row : schemas) {
                execute(backend, "drop database if exists `" + row.values().iterator().next() + "`");
            }
        }
    }

    private static void docker(String... args) throws Exception {
        Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        if (rc != 0) {
            throw new IllegalStateException("docker " + String.join(" ", args) + " rc=" + rc + ": " + out);
        }
    }

    /** 幂等解冻：容器未暂停时 docker unpause 报错，忽略—— teardown/finally 兜底用。 */
    private static void safeUnpause() {
        try {
            docker("docker", "unpause", BACKEND_CONTAINER);
        } catch (Throwable ignore) {
            // 已解冻/容器不在：幂等跳过。
        }
    }

    private static void closeQuietly(Connection c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (Throwable ignore) {
            // best-effort
        }
    }
}