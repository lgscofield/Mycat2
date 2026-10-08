/**
 * Copyright (C) <2021>  <chen junwen>
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package io.mycat.vertx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.mycat.newquery.MysqlCollector;
import io.mycat.newquery.NewMycatConnection;
import io.vertx.core.Promise;

/**
 * I-DEBT-30 面①：流式生产侧必达终结（回归锁）。
 *
 * 复现真挂死子模式——连接异步到达后 {@code prepareQuery} 抛同步异常：{@code Observable.create} 的
 * 同步 try/catch 窗口此时已关闭，异常逃逸到 Vert.x 分发线程（即拖垮 8066 的那条路径），下游 RxJava
 * 观察者收不到 {@code onError}，promise 永不 settle。修复后 {@code onSuccess} 体包 try/catch，把逃逸
 * 异常路由成 <b>恰好一次</b> {@code emitter.onError}（AtomicBoolean 单次终态门防 double-terminal）。
 */
class StreamingProducerErrorTest {

    @Test
    void producerRoutesEscapeToOnErrorExactlyOnce() {
        NewMycatConnection conn = mock(NewMycatConnection.class);
        // 锁定生产实际调用的 (String, List, MysqlCollector) void 重载，避开 (String, List, int) 歧义。
        doThrow(new RuntimeException("escape")).when(conn)
                .prepareQuery(any(String.class), any(), any(MysqlCollector.class));

        // 用未完成的 Promise 复刻"连接异步到达"：subscribe 时 onSuccess 尚未触发，
        // create 的同步兜底窗口已关闭；稍后 complete 时抛错才会逃逸成 Undeliverable。
        Promise<NewMycatConnection> connectionPromise = Promise.promise();
        AtomicInteger errCount = new AtomicInteger();
        AtomicInteger completeCount = new AtomicInteger();

        VertxExecuter.runQueryOutputAsMysqlPayloadObject(connectionPromise.future(), "sql",
                Collections.emptyList())
                .subscribe(o -> {
                }, t -> errCount.incrementAndGet(), () -> completeCount.incrementAndGet());

        try {
            connectionPromise.complete(conn);
        } catch (Throwable ignored) {
            // 修复前的逃逸面：异常抛回调用线程（正是拖垮 worker 的子模式）。
            // 此处隔离它，以便用下游计数区分红/绿——关键断言是"下游是否收到合法终态"。
        }

        // 必达且仅一次 onError；成功路径不得被误触发。
        assertEquals(1, errCount.get());
        assertEquals(0, completeCount.get());
    }
}
