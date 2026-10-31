/**
 * Copyright [2021] [chen junwen]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.mycat.xa;

import cn.mycat.vertx.xa.XaLog;
import cn.mycat.vertx.xa.impl.LocalXaSqlConnection;
import cn.mycat.vertx.xa.impl.XaLogImpl;
import com.alibaba.druid.util.JdbcUtils;
import io.mycat.newquery.NewMycatConnection;
import io.mycat.protocol.mysql.type.MySQLIsolation;
import io.vertx.core.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.mycat.xa.XaTestSuite.DB1;
import static io.mycat.xa.XaTestSuite.DB2;
import static io.mycat.xa.XaTestSuite.demoConfig;

/**
 * I-DEBT-72 回归锁：{@code LocalXaSqlConnection} 提交失败必须<b>如实上报</b>，且失败连接不能把
 * 未回滚的事务还回连接池（Druid 对 {@code underlyingAutoCommit=true} 的连接不做 recycle 回滚，
 * 而 Mycat 的事务是用 SQL {@code begin;} 开的）。<p>
 * 两个用例分别覆盖 {@code commit()} 的两条分支：双参与者 XA 路径与单数据源本地路径。
 */
@net.jcip.annotations.NotThreadSafe
public class LocalXaCommitFailureTest {
    private static TestMySQLManagerImpl mySQLManager;
    private XaLog xaLog;

    @BeforeAll
    public static void setUp() throws Exception {
        mySQLManager = new TestMySQLManagerImpl(Arrays.asList(demoConfig("ds1", 3306)
                , demoConfig("ds2", 3307)));
        initBackend();
    }

    @AfterAll
    public static void tearDown() {
        mySQLManager.close();
    }

    @BeforeEach
    public void resetData() throws Exception {
        xaLog = XaLogImpl.createXaLog(mySQLManager);
        clearTravel();
    }

    @AfterEach
    public void rollbackLeftovers() throws Exception {
        rollbackPrepared(DB1);
        rollbackPrepared(DB2);
    }

    /**
     * 双参与者（ds1 本地事务 + ds2 XA）走 {@code commitXa}：协调者日志写失败时
     * 当前实现只 LOGGER.error 就 close，调用侧收到成功——提交失败被吞、连接携带行锁回池。
     */
    @Test
    public void xaCommitFailureIsReportedAndDoesNotLeakLocks() throws Exception {
        int preparedBefore = countPreparedXa(DB2);
        LocalXaSqlConnection xa = new LocalXaSqlConnection(MySQLIsolation.DEFAULT, () -> mySQLManager, xaLog);
        get(xa.begin());
        NewMycatConnection ds1 = get(xa.getConnection("ds1"));
        NewMycatConnection ds2 = get(xa.getConnection("ds2"));
        Assertions.assertEquals(1, get(ds1.insert("INSERT INTO db1.travelrecord (id) VALUES (11)")).getAffectRows());
        Assertions.assertEquals(1, get(ds2.insert("INSERT INTO db1.travelrecord (id) VALUES (12)")).getAffectRows());

        // 让协调者日志写不进去：这正是全新 backend 上的真实故障形态（ERROR 1146）
        dropXaLog();
        Future<Void> commit;
        try {
            commit = xa.commit();
            awaitQuietly(commit);
        } finally {
            createXaLog();
        }

        int preparedAfter = countPreparedXa(DB2);
        Assertions.assertAll(
                () -> Assertions.assertTrue(commit.failed(),
                        "协调者日志写失败时 commit() 必须对外报失败，实际报成功（I-DEBT-72 ①）"),
                () -> Assertions.assertFalse(xa.isInTransaction(),
                        "提交失败后事务状态必须归零"),
                () -> assertRowUnlocked(DB1, 11),
                () -> Assertions.assertEquals(preparedBefore, preparedAfter,
                        "失败提交留下的 XA PREPARED 必须被回滚，不能残留（I-DEBT-72 ②）"));
    }

    /**
     * 单数据源（map 为空）走本地 {@code commit;}：注入 commit 失败，验证失败上报 + 锁释放。
     */
    @Test
    public void localCommitFailureIsReportedAndDoesNotLeakLocks() throws Exception {
        TestMySQLManagerImpl failingManager = managerFailingCommitOn("ds1");
        try {
            LocalXaSqlConnection xa = new LocalXaSqlConnection(MySQLIsolation.DEFAULT,
                    () -> failingManager, XaLogImpl.createXaLog(failingManager));
            get(xa.begin());
            NewMycatConnection ds1 = get(xa.getConnection("ds1"));
            Assertions.assertEquals(1, get(ds1.insert("INSERT INTO db1.travelrecord (id) VALUES (21)")).getAffectRows());

            Future<Void> commit = xa.commit();
            awaitQuietly(commit);

            Assertions.assertAll(
                    () -> Assertions.assertTrue(commit.failed(),
                            "本地 commit 失败必须对外报失败，实际报成功（I-DEBT-72 ①）"),
                    () -> Assertions.assertFalse(xa.isInTransaction(),
                            "提交失败后事务状态必须归零"),
                    () -> assertRowUnlocked(DB1, 21));
        } finally {
            failingManager.close();
        }
    }

    // ------------------------------------------------------------------ 故障注入

    private static TestMySQLManagerImpl managerFailingCommitOn(String target) {
        return new TestMySQLManagerImpl(Arrays.asList(demoConfig("ds1", 3306)
                , demoConfig("ds2", 3307))) {
            @Override
            public Future<NewMycatConnection> getConnection(String targetName) {
                return super.getConnection(targetName)
                        .map(connection -> target.equals(targetName) ? failOnCommit(connection) : connection);
            }
        };
    }

    /** 只把 {@code commit;} 这条语句打成失败，其余语句透传。 */
    private static NewMycatConnection failOnCommit(NewMycatConnection delegate) {
        return (NewMycatConnection) Proxy.newProxyInstance(
                NewMycatConnection.class.getClassLoader(),
                new Class[]{NewMycatConnection.class},
                (proxy, method, args) -> {
                    if ("update".equals(method.getName()) && args != null && args.length == 1
                            && "commit;".equals(args[0])) {
                        return Future.failedFuture(new SQLException("injected commit failure"));
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    // ------------------------------------------------------------------ 后端探针

    private static void initBackend() throws SQLException {
        for (String url : new String[]{DB1, DB2}) {
            try (Connection connection = XaTestSuite.getMySQLConnection(url)) {
                XaTestSuite.initSchema(connection);
            }
        }
    }

    private static void clearTravel() throws SQLException {
        for (String url : new String[]{DB1, DB2}) {
            try (Connection connection = XaTestSuite.getMySQLConnection(url)) {
                JdbcUtils.execute(connection, "delete from db1.travelrecord");
            }
        }
    }

    private static void dropXaLog() throws SQLException {
        for (String url : new String[]{DB1, DB2}) {
            try (Connection connection = XaTestSuite.getMySQLConnection(url)) {
                JdbcUtils.execute(connection, "DROP TABLE IF EXISTS `mycat`.`xa_log`");
            }
        }
    }

    private static void createXaLog() throws SQLException {
        initBackend();
    }

    private static int countPreparedXa(String url) throws SQLException {
        try (Connection connection = XaTestSuite.getMySQLConnection(url)) {
            return JdbcUtils.executeQuery(connection, "XA RECOVER", Collections.emptyList()).size();
        }
    }

    private static void rollbackPrepared(String url) throws SQLException {
        try (Connection connection = XaTestSuite.getMySQLConnection(url)) {
            List<Map<String, Object>> recovered =
                    JdbcUtils.executeQuery(connection, "XA RECOVER", Collections.emptyList());
            for (Map<String, Object> row : recovered) {
                JdbcUtils.execute(connection, "xa rollback '" + row.get("data") + "'");
            }
        }
    }

    /** 锁若被还池的连接带走，这条 UPDATE 会在 {@code innodb_lock_wait_timeout=1} 后抛错。 */
    private static void assertRowUnlocked(String url, long id) throws SQLException {
        try (Connection connection = XaTestSuite.getMySQLConnection(url)) {
            JdbcUtils.executeUpdate(connection, "SET SESSION innodb_lock_wait_timeout = 1",
                    Collections.emptyList());
            JdbcUtils.executeUpdate(connection, "UPDATE db1.travelrecord SET days = 0 WHERE id = " + id,
                    Collections.emptyList());
        }
    }

    // ------------------------------------------------------------------ 异步等待

    private static <T> T get(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(120, TimeUnit.SECONDS);
    }

    private static void awaitQuietly(Future<?> future) {
        try {
            future.toCompletionStage().toCompletableFuture().handle((result, error) -> null)
                    .get(180, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("future 未在预期时间内完成", e);
        }
    }
}
