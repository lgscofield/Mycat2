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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * I-DEBT-81 §8.5：语句级排空（{@code closeStatementState()}）在 GLOBAL/索引表 update 展开的
 * {@code finally} 里被同步等待。排空被真正等待后，其失败/超时会以 checked 异常从 {@code finally}
 * 抛出，进而吞掉 {@code try} 里已算好的返回结果。本工具把"等一等以加速回收"与"绝不影响语句结果"
 * 分离：超时或失败只记 warn，中断则恢复中断标志，任何情况下都不向上抛。
 */
public final class StatementStateReclaim {

    private static final Logger LOGGER = LoggerFactory.getLogger(StatementStateReclaim.class);

    private StatementStateReclaim() {
    }

    /**
     * 尽力等待排空 Future 至多 {@code timeoutMillis} 毫秒；等不到或失败只记日志，绝不抛出。
     *
     * @param future        {@code closeStatementState()} 的返回，可为 null（视为无需等待）
     * @param timeoutMillis 等待预算
     */
    public static void awaitQuietly(Future<Void> future, long timeoutMillis) {
        if (future == null) {
            return;
        }
        try {
            future.toCompletionStage().toCompletableFuture()
                    .get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 尊重中断语义：恢复标志后返回，不吞中断，也不影响语句结果。
            Thread.currentThread().interrupt();
            LOGGER.warn("await statement state drain interrupted", e);
        } catch (TimeoutException e) {
            // 排空只是加速回收，超时说明后端归还慢/悬挂；放弃等待但保留已算好的语句结果。
            LOGGER.warn("await statement state drain timeout after {} ms", timeoutMillis, e);
        } catch (Exception e) {
            // ExecutionException 及其它：排空腿失败已由 closeStatementState 语义处理，
            // 这里不得让 finally 的异常覆盖正常返回值。
            LOGGER.warn("await statement state drain failed", e);
        }
    }
}
