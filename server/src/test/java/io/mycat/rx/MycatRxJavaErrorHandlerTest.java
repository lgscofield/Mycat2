/**
 * Copyright (C) <2021>  <chen junwen>
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package io.mycat.rx;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.io.IOException;
import java.net.SocketException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import io.reactivex.rxjava3.exceptions.CompositeException;
import io.reactivex.rxjava3.exceptions.UndeliverableException;
import io.reactivex.rxjava3.functions.Consumer;

/**
 * I-DEBT-30 面①：全局 RxJava 错误兜底（回归锁）。
 *
 * 锁定核心契约——handler 对任何不可投递错误都 <b>只记日志、绝不 rethrow</b>。默认无 handler 时 RxJava 会把
 * {@link UndeliverableException} 抛回线程，打死 Vert.x worker（= 拖垮 8066）。客户端断连类异常应安静忽略。
 */
class MycatRxJavaErrorHandlerTest {

    @Test
    void handlerNeverRethrowsUndeliverable() {
        Consumer<Throwable> handler = MycatRxJavaErrorHandler.createHandler();

        // Undeliverable 包装的一般异常：解包记 WARN，不外抛。
        assertDoesNotThrow(() -> handler.accept(new UndeliverableException(new RuntimeException("boom"))));
        // 客户端断连/取消类：正常噪声，忽略且不抛。
        assertDoesNotThrow(() -> handler.accept(new UndeliverableException(new IOException("broken pipe"))));
        assertDoesNotThrow(() -> handler.accept(new UndeliverableException(new SocketException("connection reset"))));
        assertDoesNotThrow(() -> handler.accept(new UndeliverableException(new TimeoutException("t"))));
        assertDoesNotThrow(() -> handler.accept(new UndeliverableException(new InterruptedException("cancel"))));
        // 非 Undeliverable 的裸异常与 CompositeException：同样吞并记录，绝不 rethrow。
        assertDoesNotThrow(() -> handler.accept(new RuntimeException("plain")));
        assertDoesNotThrow(() -> handler.accept(new CompositeException(new RuntimeException("a"),
                new RuntimeException("b"))));
    }
}
