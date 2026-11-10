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

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-DEBT-84 事务腿 Phase 2 阶段 1：{@link XaReaper} 调度层的确定性锁——
 * ① {@code interval<=0} 禁用：{@code start()} 返 null、不排任何常驻任务（零后台线程占用、零回归）；
 * ② {@code interval>0}：按周期反复触发扫描动作。
 *
 * <p>本锁只覆盖"接线契约"（是否排定 / 是否周期触发），注入计数型 {@code sweepTask} 避开真库；扫描判定
 * 逻辑（grace 门、已决提交据 xa_log COMMIT）的正确性由活体 2PC 故障注入取证坐实（唯真机可证）。
 */
public class XaReaperSchedulingTest {

    @Test
    public void disabledWhenIntervalNonPositive() throws Exception {
        AtomicInteger runs = new AtomicInteger(0);
        XaReaper reaper = new XaReaper(() -> null, 0L, 60_000L);
        reaper.sweepTask = runs::incrementAndGet;

        assertNull(reaper.start(), "interval<=0 时不得排定任何任务");
        assertTrue(reaper.isDisabled());
        Thread.sleep(120L);
        assertEquals(0, runs.get(), "禁用态不得触发任何扫描");
    }

    @Test
    public void schedulesRecurringSweepWhenEnabled() throws Exception {
        AtomicInteger runs = new AtomicInteger(0);
        XaReaper reaper = new XaReaper(() -> null, 20L, 60_000L);
        reaper.sweepTask = runs::incrementAndGet;

        assertNotNull(reaper.start(), "interval>0 时应返回已排定的 future");
        // 最多等 ~1.5s，让周期任务至少跑几轮（首个延迟 20ms + 反复）。
        long deadline = System.currentTimeMillis() + 1500L;
        while (runs.get() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        reaper.stop();
        int observed = runs.get();
        assertTrue(observed >= 2, "启用态应周期触发扫描，实测 " + observed + " 次");
    }

    /**
     * 常驻任务不得因单轮异常静默死亡：{@code scheduleWithFixedDelay} 的任务一旦抛异常，
     * 后续执行被永久抑制。默认 sweepTask 若直连 {@code runSweep}（manager 取数段在 try 外），
     * 一次后端取数失败 = reaper 永久停摆。本锁用真实调度器 + 恒抛异常的 supplier 钉死
     * "首轮失败后仍继续周期触发"（即默认接线必须走 {@code runSweepQuiet}）。
     */
    @Test
    public void recurringSweepSurvivesSweepFailure() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            XaReaper reaper = new XaReaper(() -> {
                calls.incrementAndGet();
                throw new IllegalStateException("simulated manager lookup failure");
            }, 20L, 60_000L, scheduler, System::currentTimeMillis);

            assertNotNull(reaper.start());
            long deadline = System.currentTimeMillis() + 1500L;
            while (calls.get() < 3 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
            reaper.stop();
            assertTrue(calls.get() >= 3,
                    "单轮扫描抛异常不得杀死常驻任务，实测仅触发 " + calls.get() + " 次");
        } finally {
            scheduler.shutdownNow();
        }
    }
}
