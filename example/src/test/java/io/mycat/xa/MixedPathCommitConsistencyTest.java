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

import cn.mycat.vertx.xa.MySQLManager;
import cn.mycat.vertx.xa.impl.XaReaper;
import com.alibaba.druid.util.JdbcUtils;
import io.mycat.newquery.NewMycatConnection;
import io.vertx.core.Future;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * I-DEBT-84 Phase2 阶段2（spec §8.6 决定点归位）混合路径跨库一致性设计锁。
 *
 * <p>真后端 ds1:3306 / ds2:3307，纯 JDBC + 既有 {@link XaReaper#sweep} 复现"本地协调 ds1 + XA 参与者 ds2"
 * 在 force-close 下的两种顺序，把 PROBE-A/B 的取证结论永久化为回归锁：
 * <ul>
 *   <li><b>决定点前被关</b>（参与者意向尚未 durable）⇒ reaper 未决回滚参与者 ⇒ 两库一致回滚；</li>
 *   <li><b>决定点后</b>（ds1 已 commit、协调者意向 durable）⇒ reaper 命中意向提交参与者 ⇒ 两库一致提交，不丢已决。</li>
 * </ul>
 * 本锁钉住"决定点（ds1 本地 commit;）必须先于参与者意向 durable"这一不变量——生产侧由
 * {@code CommitDecidedFlagTest}（va，驱动真实 commit）覆盖；本锁是其跨库语义的真后端对照。
 */
public class MixedPathCommitConsistencyTest {

    private static final String URL1 = XaTestSuite.DB1;
    private static final String URL2 = XaTestSuite.DB2;

    private Connection raw(String url) throws Exception {
        Properties p = new Properties();
        p.setProperty("user", "root");
        p.setProperty("password", "123456");
        return DriverManager.getConnection(url, p);
    }

    private void exec(Connection c, String sql) throws Exception {
        JdbcUtils.execute(c, sql);
    }

    private boolean rowVisible(String url, long id) throws Exception {
        try (Connection c = raw(url)) {
            List<Map<String, Object>> rows = JdbcUtils.executeQuery(c,
                    "select id from db1.travelrecord where id=" + id, Collections.emptyList());
            return !rows.isEmpty();
        }
    }

    private boolean intentPresent(String url, long xid) throws Exception {
        try (Connection c = raw(url)) {
            List<Map<String, Object>> rows = JdbcUtils.executeQuery(c,
                    "select xid from mycat.xa_log where xid=" + xid, Collections.emptyList());
            return !rows.isEmpty();
        }
    }

    private void cleanup(long xid, long id1, long id2) throws Exception {
        for (String url : new String[]{URL1, URL2}) {
            try (Connection c = raw(url)) {
                XaTestSuite.initSchema(c);
                for (Map<String, Object> r : JdbcUtils.executeQuery(c, "XA RECOVER", Collections.emptyList())) {
                    try {
                        exec(c, "xa rollback '" + r.get("data") + "'");
                    } catch (Exception ignore) {
                        // 已被收敛的分支：幂等跳过。
                    }
                }
                exec(c, "delete from mycat.xa_log where xid=" + xid);
                exec(c, "delete from db1.travelrecord where id in (" + id1 + "," + id2 + ")");
            }
        }
    }

    private XaReaper reaper() {
        MySQLManager mgr = new MySQLManager() {
            public Future<NewMycatConnection> getConnection(String t) {
                throw new UnsupportedOperationException();
            }

            public int getAvailableNumber(String t) {
                return 0;
            }

            public int getSessionCount(String t) {
                return 0;
            }

            public Map<String, Connection> getWriteableConnectionMap() {
                return Collections.emptyMap();
            }

            public Connection getWriteableConnection(String t) {
                try {
                    return raw("ds1".equals(t) ? URL1 : URL2);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            public Future<Void> close() {
                return Future.succeededFuture();
            }

            public Future<Map<String, Integer>> computeConnectionUsageSnapshot() {
                return Future.succeededFuture(Collections.emptyMap());
            }

            public void setTimer(long delay, Runnable h) {
            }
        };
        return new XaReaper(() -> mgr, 30000, 0,
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(), System::currentTimeMillis);
    }

    private void sweepBoth() {
        Map<String, Connection> m = new HashMap<>();
        try {
            m.put("ds1", raw(URL1));
            m.put("ds2", raw(URL2));
            reaper().sweep(m);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 决定点前被 force-close：参与者意向尚未 durable（修正顺序）⇒ 无任何意向 ⇒ reaper 未决回滚参与者 ⇒ 两库一致回滚。
     * 对立面（PROBE-A 破口）：若意向提前 durable，reaper 会误提交参与者造成不一致——本锁钉住修正后的正确语义。
     */
    @Test
    public void mixedPath_forceCloseBeforeLocalCommit_staysConsistent() throws Exception {
        long xid = 984002L, id1 = 903L, id2 = 904L;
        cleanup(xid, id1, id2);
        try {
            Connection ds1 = raw(URL1);
            ds1.setAutoCommit(false);
            exec(ds1, "insert into db1.travelrecord(id) values(" + id1 + ")");
            exec(ds1, "replace into mycat.xa_log(xid) values(" + xid + ")");

            Connection ds2 = raw(URL2);
            exec(ds2, "XA START '" + xid + "'");
            exec(ds2, "insert into db1.travelrecord(id) values(" + id2 + ")");
            exec(ds2, "XA END '" + xid + "'");
            exec(ds2, "XA PREPARE '" + xid + "'");
            ds2.close();
            // 修正顺序：决定（ds1 commit;）尚未做出，不写参与者意向。

            ds1.close(); // 模拟 deadline force-close 本地协调连接（提交前）→ 本地 tx 回滚

            Assertions.assertFalse(intentPresent(URL1, xid), "本地意向随 tx 回滚");
            Assertions.assertFalse(intentPresent(URL2, xid), "决定点前参与者意向不应 durable");

            sweepBoth(); // 无意向 ⇒ 未决 ⇒ grace=0 立即 XA ROLLBACK ds2

            Assertions.assertFalse(rowVisible(URL1, id1), "ds1 本地数据回滚");
            Assertions.assertFalse(rowVisible(URL2, id2), "ds2 参与者亦回滚 ⇒ 跨库一致");
        } finally {
            cleanup(xid, id1, id2);
        }
    }

    /**
     * 决定点后（ds1 已 commit、协调者意向 durable）被 force-close：reaper union 所有后端 xa_log（含 ds1）命中意向
     * ⇒ XA COMMIT 参与者 ⇒ 两库一致提交，不丢已决提交。
     */
    @Test
    public void mixedPath_forceCloseAfterLocalCommit_convergesCommit() throws Exception {
        long xid = 984003L, id1 = 905L, id2 = 906L;
        cleanup(xid, id1, id2);
        try {
            Connection ds1 = raw(URL1);
            ds1.setAutoCommit(false);
            exec(ds1, "insert into db1.travelrecord(id) values(" + id1 + ")");
            exec(ds1, "replace into mycat.xa_log(xid) values(" + xid + ")");
            exec(ds1, "commit;"); // 决定点：业务数据 + 协调者意向原子 durable
            ds1.close();

            Connection ds2 = raw(URL2);
            exec(ds2, "XA START '" + xid + "'");
            exec(ds2, "insert into db1.travelrecord(id) values(" + id2 + ")");
            exec(ds2, "XA END '" + xid + "'");
            exec(ds2, "XA PREPARE '" + xid + "'");
            ds2.close();
            // 决定已做出，参与者意向 durable（阶段0 writeCommitLog 的等价物）。
            try (Connection c = raw(URL2)) {
                exec(c, "insert into mycat.xa_log(xid,create_time,mycat_id) values(" + xid + ",1,0)");
            }

            Assertions.assertTrue(intentPresent(URL1, xid), "ds1 协调者意向已随 commit durable");

            sweepBoth(); // 命中意向 ⇒ XA COMMIT ds2

            Assertions.assertTrue(rowVisible(URL1, id1), "ds1 已提交数据可见");
            Assertions.assertTrue(rowVisible(URL2, id2), "ds2 被 reaper 提交 ⇒ 跨库一致，不丢已决");
        } finally {
            cleanup(xid, id1, id2);
        }
    }
}