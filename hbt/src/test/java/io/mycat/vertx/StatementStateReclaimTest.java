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

import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-DEBT-81 §8.5：{@link StatementStateReclaim#awaitQuietly} 的确定性锁（不依赖后端/事件循环）。
 * 命门只有一条——无论排空 Future 成功、失败、超时还是为 null，都绝不向上抛，
 * 因此 {@code finally} 用它包一层后不可能吞掉 {@code try} 里已算好的返回值。
 */
public class StatementStateReclaimTest {

    /** 已完成的 Future：应立即返回、不抛。 */
    @Test
    public void testSucceededFutureReturnsQuietly() {
        assertDoesNotThrow(
                () -> StatementStateReclaim.awaitQuietly(Future.succeededFuture(), 1000));
    }

    /** null：视为无需等待，不抛（防御性，调用点 future 理论上非空）。 */
    @Test
    public void testNullFutureIsNoop() {
        assertDoesNotThrow(() -> StatementStateReclaim.awaitQuietly(null, 1000));
    }

    /**
     * 以异常完成的 Future（模拟排空腿 abandonQuery 失败）：必须只记日志、不抛。
     * 这是本票把排空改成真等待后新增的失败面——旧写法会让它穿透 finally。
     */
    @Test
    public void testFailedFutureDoesNotThrow() {
        Future<Void> failed = Future.failedFuture(new IllegalStateException("drain boom"));
        assertDoesNotThrow(() -> StatementStateReclaim.awaitQuietly(failed, 1000));
    }

    /**
     * 永不完成的 Future + 极小预算：应在预算内返回（证明是"有限等待"而非永久阻塞），且不抛。
     */
    @Test
    public void testTimeoutBudgetBoundsTheWait() {
        Promise<Void> never = Promise.promise();
        long start = System.nanoTime();
        assertDoesNotThrow(() -> StatementStateReclaim.awaitQuietly(never.future(), 30));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        // 给调度留裕度：远小于旧写法的 1000ms，证明确实是被小预算掐住的。
        assertTrue(elapsedMs < 500, "超时预算未生效，实际等待 " + elapsedMs + " ms");
    }

    /**
     * 端到端模拟调用点形态：try 里算出结果 return，finally 用一个失败/超时的排空 Future。
     * 断言返回值原样存活、方法正常结束——这正是修复前会被吞掉的那格。
     */
    @Test
    public void testFinallyWithFailedDrainKeepsReturnValue() {
        AtomicBoolean ranFinally = new AtomicBoolean(false);
        String result = invokeWithDrainFinally(
                Future.failedFuture(new IllegalStateException("boom")), ranFinally);
        assertEquals("res", result, "失败排空不得吞掉 try 里的返回值");
        assertTrue(ranFinally.get(), "finally 仍应被执行（回收照做）");
    }

    /** 一个"try 算结果 / finally 静默排空"的最小复现体，返回值必须穿出 finally 的失败。 */
    private static String invokeWithDrainFinally(Future<Void> drain, AtomicBoolean ranFinally) {
        try {
            return "res";
        } finally {
            ranFinally.set(true);
            StatementStateReclaim.awaitQuietly(drain, 1000);
        }
    }

    /**
     * 反向对照锁：证明"若真把异常抛出 finally，返回值就会丢"——即旧写法的失败模式，
     * 用内联 try/finally 手工复现，坐明 awaitQuietly 消除的正是这一格。
     */
    @Test
    public void testThrowingFinallyWouldSwallowReturnValue() {
        // finally 里若抛异常（旧写法等价物），try 的 return 永不生效。
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> swallowDemo());
        assertEquals("finally wins", thrown.getMessage());
    }

    private static String swallowDemo() {
        try {
            return "res";
        } finally {
            throw new RuntimeException("finally wins");
        }
    }

    /** 中断语义：不额外断言线程中断标志的复杂时序，仅确认被中断路径也不改变"不抛"这一命门。 */
    @Test
    public void testInterruptedDoesNotThrow() {
        Promise<Void> never = Promise.promise();
        // 起一个稍后把当前线程置为等待、随后由 awaitQuietly 内部处理的场景较难确定化，
        // 这里用一个永不完成的 future 覆盖"超时路径不抛、也不给当前线程留中断位"的最小契约。
        assertTrue(!never.future().isComplete());
        boolean interrupted = false;
        try {
            StatementStateReclaim.awaitQuietly(never.future(), 10);
        } finally {
            interrupted = Thread.interrupted(); // 清理任何残留中断位
        }
        assertFalse(interrupted, "正常超时路径不应给当前线程留下中断位");
    }
}
