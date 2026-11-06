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
package cn.mycat.vertx.xa.impl;

import cn.mycat.vertx.xa.MySQLManager;
import com.alibaba.druid.util.JdbcUtils;
import io.mycat.ScheduleUtil;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;

import java.sql.Connection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * I-DEBT-84 事务腿 Phase 2 阶段 1：独立事务 reaper。
 *
 * <p>承阶段 0——提交意向已持久化到后端 {@code mycat.xa_log}。本 reaper 周期性扫描各后端 {@code XA RECOVER}
 * 得到的 past-prepare in-doubt 分支并做有界收敛：
 * <ul>
 *   <li><b>已决提交</b>（xid 命中 xa_log 意向行）⇒ 对各目标 {@code XA COMMIT} + 删意向；</li>
 *   <li><b>未决</b>（不在 xa_log）⇒ 仅当"连续观察到该 xid 已滞留超过 grace"才 {@code XA ROLLBACK}，
 *       否则本轮跳过。</li>
 * </ul>
 *
 * <p><b>不抢方向盘</b>：在线 {@code BaseXaSqlConnection} 正在驱动的 2PC，其 xid 由协调器在正常窗口内自行
 * 提交/回滚，reaper 靠 grace 门（firstSeen 跨轮计时）退让，只在协调器已失联（进程重启/异常退出）致 xid
 * 长期悬挂时才接管；对已处理分支 {@code XA COMMIT/ROLLBACK} 幂等、意向删除按 {@code where xid=?} 条件删，
 * 与在线协调器并发命中同一 xid 也安全。启动一次性扫描（{@link XaLogImpl#readXARecoveryLog()}）保持原
 * eager 语义不变，本类专责常驻、grace 化的收敛。
 */
public class XaReaper {
    private final static Logger LOGGER = LoggerFactory.getLogger(XaReaper.class);

    private final Supplier<MySQLManager> mySQLManagerSupplier;
    private final long intervalMillis;
    private final long graceMillis;
    private final ScheduledExecutorService scheduler;
    private final LongSupplier nowMillis;
    /** 跨轮记录未决 xid 的首次观察时刻，用于 grace 计时（内存态；进程重启后由启动扫描兜底已决提交）。 */
    private final Map<String, Long> undecidedFirstSeen = new ConcurrentHashMap<>();

    /** 可注入的扫描动作（默认真实 sweep）；供调度单测替换为计数任务，避免连真库。 */
    Runnable sweepTask = this::runSweep;

    private ScheduledFuture<?> scheduled;

    public XaReaper(Supplier<MySQLManager> mySQLManagerSupplier, long intervalMillis, long graceMillis) {
        this(mySQLManagerSupplier, intervalMillis, graceMillis, ScheduleUtil.getTimer(), System::currentTimeMillis);
    }

    public XaReaper(Supplier<MySQLManager> mySQLManagerSupplier, long intervalMillis, long graceMillis,
                    ScheduledExecutorService scheduler, LongSupplier nowMillis) {
        this.mySQLManagerSupplier = mySQLManagerSupplier;
        this.intervalMillis = intervalMillis;
        this.graceMillis = graceMillis;
        this.scheduler = scheduler;
        this.nowMillis = nowMillis;
    }

    /**
     * 启动常驻扫描；interval<=0 视为禁用（不排任何任务、零后台线程占用）。返回已排定的 future 或 null。
     */
    public synchronized ScheduledFuture<?> start() {
        if (intervalMillis <= 0) {
            LOGGER.info("XaReaper disabled (intervalMillis=" + intervalMillis + ")");
            return null;
        }
        if (scheduled != null) {
            return scheduled;
        }
        scheduled = scheduler.scheduleWithFixedDelay(sweepTask, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        LOGGER.info("XaReaper scheduled: interval=" + intervalMillis + "ms grace=" + graceMillis + "ms");
        return scheduled;
    }

    public synchronized void stop() {
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
        }
    }

    boolean isDisabled() {
        return intervalMillis <= 0;
    }

    /** 真实扫描：任何单轮异常都不得杀死常驻任务（捕获后记 WARN，下轮重试）。 */
    void runSweepQuiet() {
        try {
            runSweep();
        } catch (Throwable t) {
            LOGGER.warn("XaReaper sweep round failed, will retry next tick", t);
        }
    }

    void runSweep() {
        MySQLManager manager = mySQLManagerSupplier.get();
        if (manager == null) {
            return;
        }
        sweep(manager.getWriteableConnectionMap());
    }

    /**
     * 对给定后端连接集做一轮 grace 化收敛。public 以便集成测试直连真 XA 后端确定性验证
     * （不依赖 {@link MySQLManager#getWriteableConnectionMap} 的重装配）。本方法负责关闭传入的连接。
     */
    public void sweep(Map<String, Connection> connectionMap) {
        if (connectionMap == null || connectionMap.isEmpty()) {
            return;
        }
        long now = nowMillis.getAsLong();
        try {
            // 幂等收口表结构（旧库补列），失败不阻断本轮。
            for (Connection connection : connectionMap.values()) {
                try {
                    XaLogSchema.ensureSchema(connection);
                } catch (Throwable t) {
                    LOGGER.warn("XaReaper ensureSchema skip: " + t.getMessage());
                }
            }

            // 1) 收集各后端 prepared 分支：xid -> 命中的 target 集。
            Map<String, Set<String>> preparedTargets = new HashMap<>();
            for (Map.Entry<String, Connection> e : connectionMap.entrySet()) {
                String target = e.getKey();
                List<Map<String, Object>> rows;
                try {
                    rows = JdbcUtils.executeQuery(e.getValue(), "XA RECOVER", java.util.Collections.emptyList());
                } catch (Throwable t) {
                    LOGGER.warn("XaReaper XA RECOVER failed on " + target + ": " + t.getMessage());
                    continue;
                }
                for (Map<String, Object> row : rows) {
                    String xid = (String) row.get("data");
                    preparedTargets.computeIfAbsent(xid, k -> new HashSet<>()).add(target);
                }
            }

            // 2) 收集持久化的提交意向：xid -> create_time（阶段 0 落盘）。
            Set<String> commitIntent = new HashSet<>();
            for (Connection connection : connectionMap.values()) {
                try {
                    List<Map<String, Object>> rows = JdbcUtils.executeQuery(connection,
                            "select xid from `" + XaLogSchema.DATABASE + "`.`" + XaLogSchema.TABLE + "`",
                            java.util.Collections.emptyList());
                    for (Map<String, Object> row : rows) {
                        commitIntent.add(java.util.Objects.toString(row.get("xid")));
                    }
                } catch (Throwable t) {
                    LOGGER.warn("XaReaper select xa_log failed: " + t.getMessage());
                }
            }

            // 3) 逐个 prepared xid 决策。
            for (Map.Entry<String, Set<String>> e : preparedTargets.entrySet()) {
                String xid = e.getKey();
                Set<String> targets = e.getValue();
                if (commitIntent.contains(xid)) {
                    // 已决提交：COMMIT 各分支 + 清意向。
                    resolve(e.getValue(), xid, "XA COMMIT", true);
                } else {
                    // 未决：靠 firstSeen 跨轮计时，滞留超 grace 才判协调器失联并回滚（不抢方向盘）。
                    Long seen = undecidedFirstSeen.putIfAbsent(xid, now);
                    long first = (seen == null) ? now : seen;
                    if (now - first >= graceMillis) {
                        resolve(targets, xid, "XA ROLLBACK", false);
                    }
                    // 未达 grace：本轮跳过，交在线协调器收敛。
                }
            }

            // 4) 清理：不再处于 prepared 的 xid（已被在线/本轮收敛）移出 firstSeen。
            undecidedFirstSeen.keySet().retainAll(preparedTargets.keySet());
        } finally {
            connectionMap.forEach((k, v) -> JdbcUtils.close(v));
        }
    }

    private void resolve(Set<String> targets, String xid, String verb, boolean alsoDeleteIntent) {
        for (String target : targets) {
            Connection connection = null;
            try {
                connection = mySQLManagerSupplier.get().getWriteableConnection(target);
                JdbcUtils.executeUpdate(connection, verb + " '" + xid + "'", java.util.Collections.emptyList());
                if (alsoDeleteIntent) {
                    XaLogSchema.deleteCommitIntent(connection, xid);
                }
            } catch (Throwable t) {
                // unknown XID 等：说明已被在线协调器或上轮处理，属幂等成功，静默。
                LOGGER.debug("XaReaper " + verb + " xid=" + xid + " on " + target + " note: " + t.getMessage());
            } finally {
                JdbcUtils.close(connection);
            }
        }
        undecidedFirstSeen.remove(xid);
    }
}
