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
import cn.mycat.vertx.xa.XaSqlConnection;
import io.mycat.newquery.NewMycatConnection;
import io.mycat.protocol.mysql.type.MySQLIsolation;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * I-DEBT-81：XA 轨（LocalXaSqlConnection）autocommit 下每条语句借出的 extraConnections，
 * 必须在 closeStatementState() 的 Future 完成之前全部归还，且归还不得被串化成 N 倍延迟。
 *
 * 依据（当前码 file:line 见 spec 2026-11-04-i-debt-81-xa-drain-await-design.md §2）：
 * - M2 LocalXaSqlConnection:145-148 非事务分支每语句无条件借一条并 add 进 extraConnections；
 * - M3 BaseXaSqlConnection:445-455 用 onComplete 丢弃 clearConnections() 的 Future ⇒ 排空不被等待；
 * - M4 BaseXaSqlConnection:478-498 逐条 compose 串行链，clear() 挂在链尾；
 * - M7 LocalSqlConnection:184-200 proxy 轨就地返回排空链 ⇒ R2 把它钉成"目标语义"的对照锁；
 * - M9 LocalXaSqlConnection:276 getAllConnections() 丢并集 ⇒ XA 轨不发 abandonQuery()
 *   （由 R4 实测挖出，R4 亦为其回归锁）。
 */
public class XaExtraConnectionDrainTest {

    private ScheduledExecutorService closer;
    private AtomicInteger borrowCount;
    private AtomicInteger closedCount;
    /** close() 被真正发起的次数。“单条失败不得阻塞其余归还”只能看这个，成功数区分不了发起与未发起。 */
    private AtomicInteger closeInvokeCount;
    /** 非空时让所有 fakes 的 abandonQuery() 以该原因失败，用于 R4 的语句腿失败格。 */
    private AtomicReference<Throwable> abandonFailureRef;
    /** 非空且 `closeFailureSeq` 命中时，该 fake 的 close() 以此原因失败（延迟之后失败而非成功），用于 R5。 */
    private AtomicReference<Throwable> closeFailureRef;
    /** 不为 -1 时，只有序号等于该值的 fake 的 close() 以 `closeFailureRef` 失败，用于 R5 的“单条归还失败”格。 */
    private AtomicInteger closeFailureSeq;

    @Before
    public void setUp() {
        closer = Executors.newSingleThreadScheduledExecutor();
        borrowCount = new AtomicInteger();
        closedCount = new AtomicInteger();
        closeInvokeCount = new AtomicInteger();
        abandonFailureRef = new AtomicReference<>();
        closeFailureRef = new AtomicReference<>();
        closeFailureSeq = new AtomicInteger(-1);
    }

    @After
    public void tearDown() {
        closer.shutdownNow();
    }

    /** MySQLManager 替身：每次 getConnection 都发一条全新假连接，复刻"autocommit 不复用"的形态。 */
    private MySQLManager newManager(long closeLatencyMillis) {
        MySQLManager manager = mock(MySQLManager.class);
        when(manager.getConnection(anyString())).thenAnswer(
                invocation -> Future.succeededFuture(newFakeConnection(closeLatencyMillis)));
        return manager;
    }

    /**
     * NewMycatConnection 替身：close() 不在调用线程完成，而是由另一线程在给定延迟后才完成，
     * 对应 M5（NewMycatConnectionImpl:643-651 的归还挂在上一条语句 Future 上、跑在 IOExecutor 里）。
     */
    private NewMycatConnection newFakeConnection(long closeLatencyMillis) {
        final int seq = borrowCount.incrementAndGet();
        NewMycatConnection connection = mock(NewMycatConnection.class);
        Promise<Void> promise = Promise.promise();
        when(connection.getTargetName()).thenReturn("ds0#" + seq);
        when(connection.abandonQuery()).thenAnswer(invocation -> {
            Throwable failure = abandonFailureRef.get();
            return failure == null ? Future.succeededFuture() : Future.failedFuture(failure);
        });
        when(connection.isClosed()).thenAnswer(invocation -> closedCount.get() >= seq);
        when(connection.close()).thenAnswer(invocation -> {
            closeInvokeCount.incrementAndGet();
            closer.schedule(() -> {
                if (closeFailureSeq.get() == seq) {
                    promise.tryFail(closeFailureRef.get());
                    return;
                }
                closedCount.incrementAndGet();
                promise.tryComplete();
            }, closeLatencyMillis, TimeUnit.MILLISECONDS);
            return promise.future();
        });
        return connection;
    }

    /** 等一个 Future 完成，超时或失败立即让测试红，避免"没等到"被误读成"等到了但计数不对"。 */
    private void awaitDone(Future<?> future, String what) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        future.onComplete(ar -> {
            if (ar.failed()) {
                failure.set(ar.cause());
            }
            latch.countDown();
        });
        assertTrue(what + " 的 Future 未在上限内完成", latch.await(20, TimeUnit.SECONDS));
        assertNull(what + " 的 Future 意外失败: " + failure.get(), failure.get());
    }

    /** 非事务态连借 n 条。 */
    private void borrowNTimes(XaSqlConnection connection, int n) throws Exception {
        for (int i = 0; i < n; i++) {
            awaitDone(connection.getConnection("ds0"), "借出第 " + (i + 1) + " 条");
        }
    }

    /**
     * 调 closeStatementState() 并等其 Future 完成，抓"完成那一瞬"的已归还数与残留列表大小。
     */
    private int[] drainAndSnapshot(XaSqlConnection connection, IntSupplier leftoverSize)
            throws Exception {
        AtomicInteger closedAtCompletion = new AtomicInteger(-1);
        AtomicInteger leftoverAtCompletion = new AtomicInteger(-1);
        CountDownLatch latch = new CountDownLatch(1);
        connection.closeStatementState().onComplete(ar -> {
            closedAtCompletion.set(closedCount.get());
            leftoverAtCompletion.set(leftoverSize.getAsInt());
            latch.countDown();
        });
        boolean completed = latch.await(20, TimeUnit.SECONDS);
        assertTrue("closeStatementState() 的 Future 未在上限内完成", completed);
        return new int[]{closedAtCompletion.get(), leftoverAtCompletion.get()};
    }

    /**
     * R1（本票主 RED）：XA 轨 autocommit 连借 5 条后，排空 Future 完成时必须 5 条都已归还。
     * 当前 M3 把 clearConnections() 的 Future 丢进 onComplete ⇒ 完成瞬间归还数为 0，必红。
     */
    @Test
    public void testR1_xaAutocommitDrainIsAwaited() throws Exception {
        LocalXaSqlConnection connection = new LocalXaSqlConnection(MySQLIsolation.DEFAULT,
                () -> newManager(150), mock(XaLog.class));
        borrowNTimes(connection, 5);
        assertEquals(5, connection.extraConnections.size());

        int[] snapshot = drainAndSnapshot(connection, () -> connection.extraConnections.size());

        assertEquals("排空 Future 完成时已归还的连接数", 5, snapshot[0]);
        assertEquals("排空 Future 完成时 extraConnections 残留数", 0, snapshot[1]);
    }

    /**
     * R2（对照锁，当前应绿）：proxy 轨 LocalSqlConnection:184-200 就地返回排空链，
     * 同一形状下完成瞬间已全部归还。作用是把目标语义钉成既有事实，防止用补 sleep 糊过 R1。
     */
    @Test
    public void testR2_proxyPolicyControlAlreadyAwaitsDrain() throws Exception {
        LocalSqlConnection connection = new LocalSqlConnection(MySQLIsolation.DEFAULT,
                () -> newManager(40), mock(XaLog.class));
        borrowNTimes(connection, 5);
        assertEquals(5, connection.extraConnections.size());

        int[] snapshot = drainAndSnapshot(connection, () -> connection.extraConnections.size());

        assertEquals(5, snapshot[0]);
        assertEquals(0, snapshot[1]);
    }

    /**
     * R3（本票第二条 RED）：20 条、每条归还延迟 100ms，排空不得是 20 段串行延迟。
     * 断言完成瞬间全部归还（INV-1）且总耗时 < 1000ms（INV-2，串行链约 2000ms）。
     */
    @Test
    public void testR3_drainIsNotSerializedByCloseLatency() throws Exception {
        LocalXaSqlConnection connection = new LocalXaSqlConnection(MySQLIsolation.DEFAULT,
                () -> newManager(100), mock(XaLog.class));
        borrowNTimes(connection, 20);

        long startNanos = System.nanoTime();
        int[] snapshot = drainAndSnapshot(connection, () -> connection.extraConnections.size());
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        assertEquals(20, snapshot[0]);
        assertEquals(0, snapshot[1]);
        assertTrue("排空耗时 " + elapsedMillis + "ms 不应是 20 段串行延迟", elapsedMillis < 1000L);
    }

    /**
     * R4（语义保真锁，实测挖出新缺陷 M9）：语句腿（abandonQuery）失败时，closeStatementState() 仍须把借出的连接
     * 归还干净（INV-1），且失败原因必须原样传播——旧写法返回的就是 future 本身，失败语义本不变；本票把"等待排空"
     * 加上后不得顺手把失败吞成成功，也不得因腿失败跳过排空。
     * 本锁首跑即红（实得 cause=null），根因不是本票改动，而是 <b>M9</b>：
     * {@code LocalXaSqlConnection.getAllConnections():276} 算出 {@code super.getAllConnections()}
     * 却从未并入返回值，
     * ⇒ XA 轨的 extras/参与者一条 abandonQuery() 都收不到 ⇒ 根本没有失败可传播。补上并集后本锁转绿。
     */
    @Test
    public void testR4_statementLegFailureStillDrainsAndKeepsCause() throws Exception {
        IllegalStateException legFailure = new IllegalStateException("abandonQuery boom");
        abandonFailureRef.set(legFailure);
        LocalXaSqlConnection connection = new LocalXaSqlConnection(MySQLIsolation.DEFAULT,
                () -> newManager(80), mock(XaLog.class));
        borrowNTimes(connection, 3);

        AtomicInteger closedAtCompletion = new AtomicInteger(-1);
        AtomicReference<Throwable> causeRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        connection.closeStatementState().onComplete(event -> {
            causeRef.set(event.cause());
            closedAtCompletion.set(closedCount.get());
            latch.countDown();
        });

        assertTrue("closeStatementState() 的 Future 未在上限内完成", latch.await(20, TimeUnit.SECONDS));
        assertSame("语句腿的失败原因不得被排空改造吞掉", legFailure, causeRef.get());
        assertEquals("失败腿同样必须把借出的连接归还干净", 3, closedAtCompletion.get());
        assertEquals(0, connection.extraConnections.size());
    }

    /**
     * R5（INV-2 语义保真锁）：单条归还失败只该条失败，其余必须照常归还，且不得把
     * 该失败伪装成成功之外的行为 —— `joinCloses` 对每条 `close()` 单独特了 `recover`（只记日志），
     * 所以整条排空腿仍应以成功完成，不该把一个无关的归还失败上抛给语句响应。
     * 旧写法下这一格不可达（逐条 compose 成串行链时，队头一条失败会直接中断后面的归还），
     * 故本锁钉住“并行 + 逐条容错 + 失败不上抛”三个语义，不是 R1/R3 的副产品。
     */
    @Test
    public void testR5_singleCloseFailureDoesNotBlockTheRestOfDrain() throws Exception {
        closeFailureRef.set(new IllegalStateException("return to pool boom"));
        closeFailureSeq.set(2);
        LocalXaSqlConnection connection = new LocalXaSqlConnection(MySQLIsolation.DEFAULT,
                () -> newManager(50), mock(XaLog.class));
        borrowNTimes(connection, 4);
        assertEquals(4, connection.extraConnections.size());

        AtomicInteger closedAtCompletion = new AtomicInteger(-1);
        AtomicInteger leftoverAtCompletion = new AtomicInteger(-1);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> causeRef = new AtomicReference<>();
        connection.closeStatementState().onComplete(event -> {
            causeRef.set(event.cause());
            closedAtCompletion.set(closedCount.get());
            leftoverAtCompletion.set(connection.extraConnections.size());
            latch.countDown();
        });

        assertTrue("closeStatementState() 的 Future 未在上限内完成", latch.await(20, TimeUnit.SECONDS));
        assertNull("单条归还失败应被 recover 吸收，不得上抛给语句响应", causeRef.get());
        assertEquals("四条归还必须全部发起（并行，不得因某条失败而短路不发起后续）", 4,
                closeInvokeCount.get());
        assertEquals("失败的归还不得阻塞其余三条归还成功", 3, closedAtCompletion.get());
        assertEquals("排空完成后已发起归还的连接不得残留在列表里", 0,
                leftoverAtCompletion.get());
    }
}
