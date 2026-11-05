/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.mycat.vertx;

import io.mycat.MycatException;
import io.mycat.beans.mycat.MycatErrorCode;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * I-DEBT-84（D-B'）：{@link StatementDeadline#withDeadline} 的确定性锁（不依赖后端 / 事件循环）。
 * 注入可控 {@link StatementDeadline.Timer} 以断言三条命门：超时界住客户端（D1）、
 * 正常先完成时定时器被取消不泄漏（D2）、禁用时零开销透传（D3）。
 */
public class StatementDeadlineTest {

    /** 记录"排了几个定时器 + 最后一个是否被取消 + 任务是否触发"的测试定时器。 */
    private static final class RecordingTimer implements StatementDeadline.Timer {
        private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
        final AtomicInteger scheduled = new AtomicInteger(0);
        final AtomicInteger fired = new AtomicInteger(0);
        final AtomicReference<ScheduledFuture<?>> last = new AtomicReference<>();

        @Override
        public ScheduledFuture<?> schedule(Runnable task, long delayMillis) {
            scheduled.incrementAndGet();
            ScheduledFuture<?> f = exec.schedule(() -> {
                fired.incrementAndGet();
                task.run();
            }, delayMillis, TimeUnit.MILLISECONDS);
            last.set(f);
            return f;
        }

        void shutdown() {
            exec.shutdownNow();
        }
    }

    private static final Supplier<Future<Void>> NOOP_ON_TIMEOUT = Future::succeededFuture;

    /**
     * D1：注入永不完成的 src，timeout=50ms ⇒ 返回 Future 应在有界时间内以 3013 MycatException 失败，
     * 且 onTimeout（force-close）被调用恰好一次（先回收再报错）。
     */
    @Test
    public void testTimeoutBoundsClientAndRunsForceCloseOnce() throws Exception {
        RecordingTimer timer = new RecordingTimer();
        try {
            Promise<String> never = Promise.promise(); // 永不 complete，模拟后端冻结
            AtomicInteger forceCloseCount = new AtomicInteger(0);
            Future<String> guarded = StatementDeadline.withDeadline(never.future(), 50, () -> {
                forceCloseCount.incrementAndGet();
                return Future.succeededFuture();
            }, timer);

            long start = System.nanoTime();
            ExecutionException ex = null;
            try {
                guarded.toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                ex = e;
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            assertNotNull(ex, "永不完成的 src 必须被 deadline 掐成失败");
            assertTrue(elapsedMs < 1000, "应在远小于 2s 的预算内超时，实际 " + elapsedMs + " ms");
            Throwable cause = ex.getCause();
            assertTrue(cause instanceof MycatException, "失败原因应为 MycatException，实得 " + cause);
            assertEquals(MycatErrorCode.ERR_SQL_DISTRIBUTED_QUERY_TIMEOUT,
                    ((MycatException) cause).getErrorCode(), "错误码须为 3013");
            assertEquals(1, forceCloseCount.get(), "onTimeout(force-close) 应被调用恰好一次");
            assertEquals(1, timer.scheduled.get(), "应只排一个定时器");
            assertEquals(1, timer.fired.get(), "定时器应触发");
        } finally {
            timer.shutdown();
        }
    }

    /**
     * D2：src 先正常完成 ⇒ 定时器必须被取消（不泄漏），onTimeout 0 次，结果原样返回。
     * 用已完成的 Future：withDeadline 内先排定时器、随后 onComplete 同步触发即刻 cancel，确定可断言。
     */
    @Test
    public void testNormalCompletionCancelsTimerNoLeak() {
        RecordingTimer timer = new RecordingTimer();
        try {
            AtomicInteger forceCloseCount = new AtomicInteger(0);
            Future<String> guarded = StatementDeadline.withDeadline(
                    Future.succeededFuture("ok"), 5000, () -> {
                        forceCloseCount.incrementAndGet();
                        return Future.succeededFuture();
                    }, timer);

            String result = guarded.result();
            assertEquals("ok", result, "正常完成结果应原样透出");
            assertEquals(1, timer.scheduled.get(), "仍应排了一个定时器");
            assertNotNull(timer.last.get());
            assertTrue(timer.last.get().isCancelled(), "src 先完成 ⇒ 定时器必须被取消，杜绝泄漏");
            assertEquals(0, forceCloseCount.get(), "未超时不得触发 force-close");
            assertEquals(0, timer.fired.get(), "定时器不应触发");
        } finally {
            timer.shutdown();
        }
    }

    /**
     * D3：timeoutMillis&lt;=0 ⇒ 原样透传同一 Future 实例（禁用零开销，不建 Promise、不排定时器）。
     */
    @Test
    public void testDisabledPassesThroughSameInstance() {
        Promise<String> src = Promise.promise();
        Future<String> guarded = StatementDeadline.withDeadline(src.future(), 0, NOOP_ON_TIMEOUT);
        assertSame(src.future(), guarded, "禁用时应返回同一 Future 实例，零包装");
        Future<String> guardedNeg = StatementDeadline.withDeadline(src.future(), -1, NOOP_ON_TIMEOUT);
        assertSame(src.future(), guardedNeg, "负值同样禁用");
    }

    /**
     * 超时后 src 迟到完成必须变 no-op（不二次结算、不改已失败的结局）——幂等 CAS 的锁。
     */
    @Test
    public void testLateSourceCompletionAfterTimeoutIsNoop() throws Exception {
        RecordingTimer timer = new RecordingTimer();
        try {
            Promise<String> late = Promise.promise();
            Future<String> guarded = StatementDeadline.withDeadline(late.future(), 30, NOOP_ON_TIMEOUT, timer);

            // 等超时先结算。
            try {
                guarded.toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
                fail("超时后不该成功完成");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof MycatException);
            }
            // 超时结算后再让 src 完成：应被 settled 幂等吞掉，结局不变（仍失败）。
            late.complete("too late");
            assertNull(guarded.result(), "src 迟到完成不得改写已超时失败的结局");
            assertTrue(guarded.failed());
        } finally {
            timer.shutdown();
        }
    }

    /**
     * onTimeout 内抛异常不得阻断向客户端报错（回收失败仍须有界返回）。
     */
    @Test
    public void testForceCloseFailureStillReportsBoundedError() throws Exception {
        RecordingTimer timer = new RecordingTimer();
        try {
            Promise<String> never = Promise.promise();
            CountDownLatch ran = new CountDownLatch(1);
            Future<String> guarded = StatementDeadline.withDeadline(never.future(), 40, () -> {
                ran.countDown();
                throw new RuntimeException("force close boom");
            }, timer);

            try {
                guarded.toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
                fail("应超时失败");
            } catch (ExecutionException ex) {
                assertTrue(ex.getCause() instanceof MycatException,
                        "force-close 抛错仍须以 3013 界住客户端");
                assertEquals(MycatErrorCode.ERR_SQL_DISTRIBUTED_QUERY_TIMEOUT,
                        ((MycatException) ex.getCause()).getErrorCode());
            }
            assertTrue(ran.await(1, TimeUnit.SECONDS), "onTimeout 应被尝试执行");
        } finally {
            timer.shutdown();
        }
    }
}
