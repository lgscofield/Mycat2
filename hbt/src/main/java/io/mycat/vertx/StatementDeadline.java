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
import io.mycat.ScheduleUtil;
import io.mycat.beans.mycat.MycatErrorCode;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * I-DEBT-84（D-B'）：给"Mycat 等后端响应"这条语句 Future 套一个 wall-clock deadline，兜住
 * 后端僵死 / TCP 黑 hole（服务端 {@code queryTimeout} 也救不了）时客户端无限挂的可用性缺口。
 *
 * <p>本工具只做两件事，缺一不算收口（否则=客户端解挂却泄漏 IOExecutor worker 线程 / 物理连接，
 * 重回 I-DEBT-80 孤儿签名）：
 * <ol>
 *   <li><b>界客户端</b>：把 {@code src} 与一个 {@code timeoutMillis} 定时器 race，先到者定胜负——
 *       定时器胜出即以 {@link MycatErrorCode#ERR_SQL_DISTRIBUTED_QUERY_TIMEOUT} 失败返回的 Future，
 *       让上层命令处理器（{@code MycatVertxMySQLHandler} 的 recover 链）照常发 ERR 包，客户端有界返回；</li>
 *   <li><b>回收资源</b>：定时器胜出时<b>先</b>跑 {@code onTimeout}（由调用方对这条命令在途的物理连接
 *       做 force-close，使阻塞在 socket read 上的 worker 抛错解困、连接作废不入池），<b>再</b>报错。</li>
 * </ol>
 *
 * <p>{@code src} 先正常完成时定时器必须被取消（{@code ScheduledFuture.cancel}），杜绝定时器泄漏；
 * {@code settled} 用 CAS 保证"先到先得、幂等只结算一次"，超时后 src 迟到完成变 no-op。
 */
public final class StatementDeadline {

    private static final Logger LOGGER = LoggerFactory.getLogger(StatementDeadline.class);

    /**
     * 定时器接缝：把"延迟触发"抽象出来，生产用 {@link ScheduleUtil#getTimer()} 单例，
     * 单测注入可控实现以断言定时器被取消（不泄漏）。
     */
    public interface Timer {
        ScheduledFuture<?> schedule(Runnable task, long delayMillis);
    }

    private static final Timer DEFAULT_TIMER =
            (task, delayMillis) -> ScheduleUtil.getTimer().schedule(task, delayMillis, TimeUnit.MILLISECONDS);

    private StatementDeadline() {
    }

    /**
     * 用默认定时器（{@link ScheduleUtil#getTimer()}）给 {@code src} 套 deadline。
     *
     * @param src           语句/命令执行链返回的 Future
     * @param timeoutMillis 超时预算（毫秒）；{@code <=0} 表示禁用，原样透传 {@code src}
     * @param onTimeout     定时器胜出时<b>先</b>执行的资源回收动作（force-close 在途连接），可返回 null 结果
     * @param <T>           Future 结果类型
     */
    public static <T> Future<T> withDeadline(Future<T> src, long timeoutMillis, Supplier<Future<Void>> onTimeout) {
        return withDeadline(src, timeoutMillis, onTimeout, DEFAULT_TIMER);
    }

    /**
     * 可注入 {@link Timer} 的实现，供单测断言定时器取消/触发次数。
     */
    public static <T> Future<T> withDeadline(Future<T> src, long timeoutMillis, Supplier<Future<Void>> onTimeout,
                                             Timer timer) {
        if (timeoutMillis <= 0) {
            // 禁用：零开销透传，不建 Promise、不排定时器（D3）。
            return src;
        }
        Promise<T> out = Promise.promise();
        AtomicBoolean settled = new AtomicBoolean(false);

        // 先排定时器，保证 handle 在任何 src 完成回调前已就绪，取消路径无竞态。
        ScheduledFuture<?> handle = timer.schedule(() -> {
            if (settled.compareAndSet(false, true)) {
                // 超时胜出：先回收资源（force-close 解困卡在读上的 worker / 作废连接），再界住客户端。
                if (onTimeout != null) {
                    try {
                        onTimeout.get();
                    } catch (Throwable t) {
                        // 回收失败不影响向客户端报错——客户端仍须有界返回。
                        LOGGER.warn("statement deadline onTimeout(force-close) failed", t);
                    }
                }
                out.tryFail(new MycatException(MycatErrorCode.ERR_SQL_DISTRIBUTED_QUERY_TIMEOUT,
                        "Query exceeded statement deadline of " + timeoutMillis + " ms while waiting for backend response"));
            }
        }, timeoutMillis);

        src.onComplete(ar -> {
            if (settled.compareAndSet(false, true)) {
                // src 先到：取消未触发的定时器，杜绝泄漏（D2）。
                if (handle != null) {
                    handle.cancel(false);
                }
                if (ar.succeeded()) {
                    out.tryComplete(ar.result());
                } else {
                    out.tryFail(ar.cause());
                }
            }
            // settled 已为 true 说明超时先结算：src 迟到完成变 no-op。
        });

        return out.future();
    }
}
