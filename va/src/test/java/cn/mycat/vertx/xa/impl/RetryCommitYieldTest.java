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
import cn.mycat.vertx.xa.XaLog;
import io.mycat.newquery.NewMycatConnection;
import io.mycat.newquery.SqlResult;
import io.mycat.protocol.mysql.type.MySQLIsolation;
import io.vertx.core.Future;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * I-DEBT-84 Phase2 阶段2 Task2（R2 retryCommit 超时让位 reaper）确定性回归锁。
 *
 * <p>commit 腿超时（{@code markCommitTimedOut()}）后，XA COMMIT 失败触发的 {@code retryCommit} 不得再
 * 无限 {@code setTimer} 抢新连接重试（客户端已拿 3013 走人，后台 churn 会与 reaper 双主）——须立即让位，
 * 收敛交 reaper（意向在 xa_log，reaper 命中意向 ⇒ XA COMMIT）。未超时则维持原重试语义（负控）。
 */
public class RetryCommitYieldTest {

    private MySQLManager manager;
    private XaLog xaLog;
    private final AtomicInteger setTimerCount = new AtomicInteger();
    private CountDownLatch setTimerLatch;

    @Before
    public void setUp() {
        setTimerLatch = new CountDownLatch(1);
        manager = mock(MySQLManager.class);
        // retryCommit 换的新连接：XA COMMIT 恒失败，其余语句成功。
        when(manager.getConnection(anyString())).thenAnswer(inv -> Future.succeededFuture(xaCommitFailingConnection()));
        // setTimer 只计数 + 放闸，不真正重排（避免无限 churn 挂死测试）。
        doAnswer(inv -> {
            setTimerCount.incrementAndGet();
            setTimerLatch.countDown();
            return null;
        }).when(manager).setTimer(anyLong(), any(Runnable.class));

        xaLog = mock(XaLog.class);
        when(xaLog.nextXid()).thenReturn("984101");
        when(xaLog.getExpires()).thenReturn(0L);
        when(xaLog.retryDelay()).thenReturn(0L);
        try {
            when(xaLog.logCommitBeforeXaCommit(anyString())).thenReturn(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private NewMycatConnection xaCommitFailingConnection() {
        NewMycatConnection c = mock(NewMycatConnection.class);
        when(c.getTargetName()).thenReturn("dsX");
        when(c.isClosed()).thenReturn(false);
        // retryCommit 的 per-connection compose 带失败兜底 `throwable -> c.close()`：XA COMMIT 失败但 close 成功
        // 会被吞成 all.onSuccess（不进 setTimer 重试分支）。要驱动真正的重试循环（后端冻死：XA COMMIT 失败且
        // 连接无法干净归还），close 也必须失败 ⇒ compose 失败 ⇒ join 失败 ⇒ all.onFailure ⇒ setTimer。
        when(c.close()).thenReturn(Future.failedFuture(new IllegalStateException("injected close failure")));
        when(c.abandonQuery()).thenReturn(Future.succeededFuture());
        when(c.update(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.startsWith("XA COMMIT")) {
                return Future.failedFuture(new IllegalStateException("injected XA COMMIT failure"));
            }
            return Future.<SqlResult>succeededFuture(null);
        });
        return c;
    }

    private BaseXaSqlConnection driveToPrepared() throws Exception {
        BaseXaSqlConnection c = new BaseXaSqlConnection(MySQLIsolation.DEFAULT, () -> manager, xaLog);
        await(c.begin());
        await(c.getConnection("ds1"));
        await(c.getConnection("ds2"));
        return c;
    }

    private static <T> T await(Future<T> f) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Object> result = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        f.onComplete(ar -> {
            if (ar.succeeded()) {
                result.set(ar.result());
            } else {
                err.set(ar.cause());
            }
            latch.countDown();
        });
        assertTrue("future 未在 20s 内完成", latch.await(20, TimeUnit.SECONDS));
        if (err.get() != null) {
            throw new AssertionError("future 失败", err.get());
        }
        @SuppressWarnings("unchecked")
        T t = (T) result.get();
        return t;
    }

    /**
     * 超时让位：markCommitTimedOut 后 XA COMMIT 失败 ⇒ retryCommit 立即退出（不 setTimer），commit 有界失败。
     * 当前生产码 retryCommit 无条件 setTimer ⇒ 本锁红（setTimerCount>=1 且 commit 不完成）。
     */
    @Test
    public void timedOut_retryCommitYieldsWithoutSetTimer() throws Exception {
        BaseXaSqlConnection c = driveToPrepared();
        c.markCommitTimedOut();

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Boolean> failed = new AtomicReference<>(false);
        c.commit().onComplete(ar -> {
            failed.set(ar.failed());
            done.countDown();
        });

        assertTrue("超时让位后 commit 须有界完成（不无限重试）", done.await(5, TimeUnit.SECONDS));
        assertTrue("让位后 commit 以失败收敛（交 reaper）", failed.get());
        assertEquals("超时让位不得再 setTimer 抢新连接重试", 0, setTimerCount.get());
    }

    /**
     * 负控：未超时 ⇒ retryCommit 维持原重试语义（setTimer 被调用）。
     */
    @Test
    public void notTimedOut_retryCommitStillRetries() throws Exception {
        BaseXaSqlConnection c = driveToPrepared();

        c.commit(); // XA COMMIT 失败 → retryCommit → setTimer（不真正重排）

        assertTrue("未超时时 retryCommit 应仍 setTimer 重试", setTimerLatch.await(5, TimeUnit.SECONDS));
        assertTrue("setTimer 至少被调一次", setTimerCount.get() >= 1);
    }
}