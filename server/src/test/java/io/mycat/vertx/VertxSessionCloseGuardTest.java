/**
 * Copyright (C) <2021>  <chen junwen>
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package io.mycat.vertx;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import io.mycat.MycatDataContext;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;

/**
 * I-DEBT-30 面①（终审修复回归锁）：{@link VertxSessionImpl#close(boolean, String)} 必须同步置
 * 写守卫，使关闭后 {@code writeBytes}/{@code writeErrorEndPacketBySyncInProcessError} 对 socket 变 no-op。
 *
 * <p>面① 的核心前提是"关连接后，顶层 recover 补发的裸 ERR 因连接已断自动变 no-op"。proxy NIO 会话靠
 * {@code channel().isConnected()} 守卫兑现；Vert.x 运行时的 {@code VertxSessionImpl} 用 {@code close}
 * 布尔守卫，但其 {@code close(...)} 方法此前只 kill 后端 + 延迟 1 秒关 socket，<b>漏置守卫</b>，导致列头
 * 已上线后中途崩时，顶层 recover 仍把协议非法的裸 0xFF ERR 写进读行态连接——正是本债要消灭的挂死面。
 */
class VertxSessionCloseGuardTest {

    @Test
    void writeErrorAfterCloseIsSuppressed() {
        MycatDataContext ctx = mock(MycatDataContext.class);
        NetSocket socket = mock(NetSocket.class);
        when(ctx.getLastErrorCode()).thenReturn(0);
        when(ctx.getLastMessage()).thenReturn("boom");
        when(ctx.getServerCapabilities()).thenReturn(0);
        // getTransactionSession() 返回 null：writeBytes(end=true) 走直写分支，不触碰 session manager。
        VertxSessionImpl session = new VertxSessionImpl(ctx, socket, null);

        // 正控制：连接存活时写操作确实落到 socket。
        session.writeBytes(new byte[]{1}, false);
        verify(socket, times(1)).write(any(Buffer.class));

        // 清掉正控制的调用记录，后续只对"关闭后"的写行为做断言（never/times 是全量计数，不重置）。
        clearInvocations(socket);

        // 关前端连接（observer.onError 的 resultStarted 分支）。
        session.close(false, "boom");

        // 顶层 recover 的终结 ERR 包必须被写守卫抑制，绝不再写进 socket。
        session.writeErrorEndPacketBySyncInProcessError();
        verify(socket, never()).write(any(Buffer.class));
    }
}
