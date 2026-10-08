/**
 * Copyright (C) <2021>  <chen junwen>
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package io.mycat.rx;

import java.io.IOException;
import java.net.SocketException;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.reactivex.rxjava3.exceptions.CompositeException;
import io.reactivex.rxjava3.exceptions.UndeliverableException;
import io.reactivex.rxjava3.functions.Consumer;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;

/**
 * 全局 RxJava 未投递错误兜底（I-DEBT-30 面①）。
 *
 * <p>RxJava 在下游已终止/取消、或订阅者未实现 onError 时，会把异常封成 {@link UndeliverableException}
 * 交给 {@link RxJavaPlugins} 的全局 handler。默认 handler 直接把异常抛回当前线程——在 Vert.x 上即打死
 * worker 线程，进而拖垮整个代理进程（看板 I-DEBT-30 记录的"拖垮 8066"）。本 handler 的契约是：
 * <b>只记日志、绝不 rethrow</b>。客户端断连/超时/取消类属正常噪声，降级为 debug 忽略；其余记 WARN。
 */
public final class MycatRxJavaErrorHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(MycatRxJavaErrorHandler.class);

    private MycatRxJavaErrorHandler() {
    }

    /** 构造全局错误 handler（可独立测试；{@link #install()} 与它分离，避免测试污染 JVM 级单例）。 */
    public static Consumer<Throwable> createHandler() {
        return throwable -> {
            if (throwable instanceof UndeliverableException) {
                Throwable cause = throwable.getCause();
                // 客户端断连/取消类：正常噪声，静默忽略（不刷错误、更不外抛）。
                if (isClientGone(cause)) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("undeliverable rx error (client gone/cancelled), ignored", cause);
                    }
                    return;
                }
                LOGGER.warn("undeliverable rx error, suppressed to keep worker alive", cause);
                return;
            }
            if (throwable instanceof CompositeException) {
                for (Throwable t : ((CompositeException) throwable).getExceptions()) {
                    LOGGER.warn("composite rx error, suppressed to keep worker alive", t);
                }
                return;
            }
            // 其余一律记 WARN，绝不 rethrow。
            LOGGER.warn("rx error not deliverable, suppressed to keep worker alive", throwable);
        };
    }

    /** 注册到 RxJava 全局插件钩子。进程内一次性调用（运行时启动早期）。 */
    public static void install() {
        RxJavaPlugins.setErrorHandler(createHandler());
    }

    private static boolean isClientGone(Throwable cause) {
        return cause instanceof IOException
                || cause instanceof SocketException
                || cause instanceof InterruptedException
                || cause instanceof TimeoutException;
    }
}
