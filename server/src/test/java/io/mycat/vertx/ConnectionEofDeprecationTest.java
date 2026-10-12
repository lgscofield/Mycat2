/**
 * Copyright (C) <2021>  <chen junwen>
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package io.mycat.vertx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.mycat.MycatDataContext;
import io.mycat.config.MySQLServerCapabilityFlags;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;

/**
 * I-VE-2 连接级 EOF 回归锁：证明结果集编码的 EOF/OK 分支由<b>每连接协商能力</b>驱动，而非启动期全局
 * static（对齐 MySQL Connection Phase：客户端在 HandshakeResponse41 声明 CLIENT_DEPRECATE_EOF 后，
 * 列定义结束与结果行结束的收尾包由 EOF 变体为"省略/OK"）。
 *
 * <p>驱动真实 {@link VertxSessionImpl}（其 {@code isDeprecateEOF()} 委派 {@code dataContext.getServerCapabilities()}，
 * 该值由 {@code VertxMySQLAuthHandler.completeAuthentication} 每连接存入客户端上报能力）+
 * 真实 {@code MySQLServerSession} 默认编码器。仅以 mock {@link MycatDataContext} 注入"这一条连接协商到了什么"，
 * 从而把"两条连接 → 不同协商位 → 不同出站包"钉成回归网。
 */
class ConnectionEofDeprecationTest {

    /** 不含 CLIENT_DEPRECATE_EOF 的基础能力（老客户端 / 5.7 前语义：仍收 EOF 收尾包）。 */
    private static int capsWithoutEof() {
        return MySQLServerCapabilityFlags.getDefaultServerCapabilities()
                & ~MySQLServerCapabilityFlags.CLIENT_DEPRECATE_EOF;
    }

    /** 在基础能力上叠加 CLIENT_DEPRECATE_EOF（新客户端：列结束省略 EOF、行结束改用 OK）。 */
    private static int capsWithEof() {
        return MySQLServerCapabilityFlags.getDefaultServerCapabilities()
                | MySQLServerCapabilityFlags.CLIENT_DEPRECATE_EOF;
    }

    private static VertxSessionImpl sessionWithCapabilities(int capabilities) {
        MycatDataContext ctx = mock(MycatDataContext.class);
        when(ctx.getServerCapabilities()).thenReturn(capabilities);
        // 令 writeBytes 走直写 socket 分支（无事务会话回收），聚焦编解码本身。
        when(ctx.getTransactionSession()).thenReturn(null);
        when(ctx.serverStatus()).thenReturn(0);
        when(ctx.getWarningCount()).thenReturn(0);
        NetSocket socket = mock(NetSocket.class);
        when(socket.write(any(Buffer.class))).thenReturn(Future.succeededFuture());
        return new VertxSessionImpl(ctx, socket, null);
    }

    private static Buffer captureWrite(NetSocket socket) {
        ArgumentCaptor<Buffer> captor = ArgumentCaptor.forClass(Buffer.class);
        verify(socket).write(captor.capture());
        return captor.getValue();
    }

    private static NetSocket socketOf(VertxSessionImpl session) {
        return session.getSocket();
    }

    /** 列结束：未协商 deprecate-eof 时，写出一枚 5 字节 EOF 包（首字节 0xFE）。 */
    @Test
    void columnEndWritesEofPacketWhenEofNotDeprecated() {
        VertxSessionImpl session = sessionWithCapabilities(capsWithoutEof());
        session.writeColumnEndPacket(false);

        Buffer written = captureWrite(socketOf(session));
        byte[] packet = written.getBytes();
        // 4 字节 MySQL 包头 + 5 字节 EOF payload。
        assertEquals(4 + 5, packet.length, "EOF 列结束包应为 4 字节头 + 5 字节 payload");
        assertEquals((byte) 0xFE, packet[4], "EOF 包首字节应为 0xFE");
    }

    /** 列结束：协商了 deprecate-eof 时，不写出任何列结束包（EOF 被省略）。 */
    @Test
    void columnEndWritesNothingWhenEofDeprecated() {
        VertxSessionImpl session = sessionWithCapabilities(capsWithEof());
        session.writeColumnEndPacket(false);

        verify(socketOf(session), never()).write(any(Buffer.class));
    }

    /** 行结束：未协商时写 EOF（5 字节 payload）。 */
    @Test
    void rowEndWritesEofPacketWhenEofNotDeprecated() {
        VertxSessionImpl session = sessionWithCapabilities(capsWithoutEof());
        session.writeRowEndPacket(false, false);

        Buffer written = captureWrite(socketOf(session));
        byte[] packet = written.getBytes();
        assertEquals(4 + 5, packet.length, "行结束 EOF 包应为 4 字节头 + 5 字节 payload");
        assertEquals((byte) 0xFE, packet[4], "EOF 包首字节应为 0xFE");
    }

    /** 行结束：协商了 deprecate-eof 时改写 OK 包（首字节仍 0xFE，但长度大于 EOF——OK 携 affected/insert/status/warnings）。 */
    @Test
    void rowEndWritesOkPacketWhenEofDeprecated() {
        VertxSessionImpl session = sessionWithCapabilities(capsWithEof());
        session.writeRowEndPacket(false, false);

        Buffer written = captureWrite(socketOf(session));
        byte[] packet = written.getBytes();
        assertEquals((byte) 0xFE, packet[4], "deprecate-eof 下行结束以 0xFE 头 OK 包收尾");
        assertTrue(packet.length > 4 + 5,
                "OK 包 payload 应长于 5 字节 EOF（携 affectedRows/lastInsertId/status/warnings）");
    }

    /**
     * 真·连接级鉴别：同一 NetSocket mock 复用两条不同 dataContext（一协商一未协商），
     * 两条连接各自按自身能力编码，互不串扰——坐实决策源自每连接 dataContext 而非全局。
     */
    @Test
    void twoSessionsEncodeIndependentlyByTheirOwnCapabilities() {
        VertxSessionImpl plain = sessionWithCapabilities(capsWithoutEof());
        VertxSessionImpl deprecated = sessionWithCapabilities(capsWithEof());

        deprecated.writeColumnEndPacket(false);
        verify(socketOf(deprecated), never()).write(any(Buffer.class));

        reset(socketOf(plain));
        when(plain.getSocket().write(any(Buffer.class))).thenReturn(Future.succeededFuture());
        plain.writeColumnEndPacket(false);
        Buffer written = captureWrite(plain.getSocket());
        assertEquals(4 + 5, written.getBytes().length, "未协商连接仍发 5 字节 EOF，不受另一连接协商位影响");
    }
}
