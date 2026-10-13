/**
 * Copyright (C) <2021>  <chen junwen>
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package io.mycat.vertx;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.IdentityHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.mycat.MetaClusterCurrent;
import cn.mycat.vertx.xa.MySQLManager;
import cn.mycat.vertx.xa.XaLog;
import io.mycat.config.MySQLServerCapabilityFlags;
import io.mycat.config.ServerConfig;
import io.mycat.config.ServerPersona;
import io.mycat.config.TlsMode;
import io.mycat.config.VersionProfile;
import io.mycat.config.VersionProfileFactory;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;

/**
 * R4/Task3 回归锁：前端握手中继在收到 {@code Protocol::SSLRequest}（明文握手后客户端请求同端口升级到 TLS 的
 * 截断 HandshakeResponse）时，必须触发 {@link NetSocket#upgradeToSsl(Handler)} 就地升级，
 * 而<b>不</b>把它当作普通 HandshakeResponse 走认证路径。
 *
 * <p>对齐 MySQL 官方 Connection Phase（服务端 Handshake 宣称 CLIENT_SSL=2048 → 客户端回 SSLRequest
 * 置 CLIENT_SSL → 同连接 TLS 握手 → TLS 上真 HandshakeResponse）。参见 design §2 / §4 方案 A。
 *
 * <p>驱动真实 {@link VertxMySQLAuthHandler}，仅 mock {@link NetSocket} 以观测"是否请求升级"这一处理器的决策
 * （传输层 upgradeToSsl 与真 Connector/J 完成 STARTTLS 握手、cipher=TLS_* 的实证由本任务的一次性探针另行坐实）。
 */
class FrontendStartTlsUpgradeTest {

    private Map<Class, Object> savedContext;

    @BeforeEach
    void setUp() {
        savedContext = MetaClusterCurrent.copyContext();
        // 构造握手需非空 VersionProfile（createHandshakePayload 直接取用），以 REQUIRED 使能力位含 CLIENT_SSL。
        VersionProfile profile = VersionProfileFactory.of(ServerPersona.MYSQL_5_7, null, TlsMode.REQUIRED);
        // MycatDataContextImpl 构造 switchTransaction(DEFAULT=PROXY) 会即时取用 XaLog（MySQLManager 为懒取 supplier），
        // 本用例不驱动查询/事务，仅注册桩件让握手构造通过。
        IdentityHashMap<Class, Object> ctx = new IdentityHashMap<>();
        ctx.put(VersionProfile.class, profile);
        ctx.put(XaLog.class, mock(XaLog.class));
        ctx.put(MySQLManager.class, mock(MySQLManager.class));
        // MycatXaTranscation 构造即时 wrapper(ServerConfig)（取默认非粘滞会话即可）。
        ctx.put(ServerConfig.class, new ServerConfig());
        MetaClusterCurrent.register(ctx);
    }

    @AfterEach
    void tearDown() {
        MetaClusterCurrent.register(savedContext);
    }

    /** int<4> 小端字节（MySQL 线格式为 little-endian；Vert.x Buffer.setInt 默认大端，不能直接用）。 */
    private static byte[] le(int v) {
        return new byte[] { (byte) (v & 0xFF), (byte) ((v >> 8) & 0xFF), (byte) ((v >> 16) & 0xFF), (byte) ((v >> 24) & 0xFF) };
    }

    /** 拼一枚完整 MySQL 包：4 字节头（3 字节小端长度 + packetId）+ 32 字节 SSLRequest payload。 */
    private static Buffer sslRequestPacket(int clientFlag, int packetId) {
        Buffer payload = Buffer.buffer();
        payload.appendBytes(le(clientFlag));  // client_flag int<4>
        payload.appendBytes(le(16777216));    // max_packet_size int<4>
        payload.appendByte((byte) 45);        // character_set int<1>
        payload.appendBytes(new byte[23]);    // filler 23×0
        return frame(payload, packetId);
    }

    /** 为 payload 前置 4 字节 MySQL 包头（3 字节小端长度 + 1 字节 packetId）。 */
    private static Buffer frame(Buffer payload, int packetId) {
        int len = payload.length();
        Buffer packet = Buffer.buffer();
        packet.appendByte((byte) (len & 0xFF));
        packet.appendByte((byte) ((len >> 8) & 0xFF));
        packet.appendByte((byte) ((len >> 16) & 0xFF));
        packet.appendByte((byte) packetId);
        packet.appendBuffer(payload);
        return packet;
    }

    private static NetSocket upgradedSocket() {
        NetSocket socket = mock(NetSocket.class);
        when(socket.write(any(Buffer.class))).thenReturn(io.vertx.core.Future.succeededFuture());
        return socket;
    }

    /** 置 CLIENT_SSL 的 SSLRequest 首包 → 必须请求 upgradeToSsl，且不落到认证写包路径。 */
    @Test
    void sslRequestTriggersUpgradeNotAuth() {
        NetSocket socket = upgradedSocket();
        int caps = MySQLServerCapabilityFlags.getDefaultServerCapabilities()
                | MySQLServerCapabilityFlags.CLIENT_SSL;
        VertxMySQLAuthHandler handler = new VertxMySQLAuthHandler(socket, caps,
                mock(io.mycat.vertx.VertxMycatServer.MycatSessionManager.class));

        // 构造阶段发出的明文 Handshake 是第 1 次 write；重置以便只观测 handle() 后的出站。
        long handshakeWrites = org.mockito.Mockito.mockingDetails(socket).getInvocations().stream()
                .filter(i -> "write".equals(i.getMethod().getName())).count();
        org.mockito.Mockito.reset(socket);
        when(socket.write(any(Buffer.class))).thenReturn(io.vertx.core.Future.succeededFuture());
        assertTrue(handshakeWrites >= 1, "构造握手时应已向 socket 写出明文 Handshake");

        int clientFlag = MySQLServerCapabilityFlags.CLIENT_SSL
                | MySQLServerCapabilityFlags.CLIENT_PROTOCOL_41
                | MySQLServerCapabilityFlags.CLIENT_SECURE_CONNECTION;
        handler.handle(sslRequestPacket(clientFlag, 1));

        // 核心鉴别：收到 SSLRequest 应就地请求升级到 TLS。
        verify(socket).upgradeToSsl(any(Handler.class));
        // 且不得把 SSLRequest 当普通 HandshakeResponse 继续走认证（不写认证结果包 / 不再设明文 handler）。
        verify(socket, never()).write(any(Buffer.class));
    }

    /** 未置 CLIENT_SSL 的首包（普通 HandshakeResponse）不应触发 TLS 升级。 */
    @Test
    void plainHandshakeResponseDoesNotTriggerUpgrade() {
        NetSocket socket = upgradedSocket();
        int caps = MySQLServerCapabilityFlags.getDefaultServerCapabilities()
                | MySQLServerCapabilityFlags.CLIENT_SSL; // 服务端广告了 SSL，但本包不含 CLIENT_SSL
        // 认证器缺省（MetaClusterCurrent 未注册 Authenticator）→ 走放行语义，需 manager/session 可构造，
        // 这里只断言"不升级"，故仅需验证 upgradeToSsl 未被调用即可；认证后续路径不在本用例断言范围。
        VertxMySQLAuthHandler handler = new VertxMySQLAuthHandler(socket, caps,
                mock(io.mycat.vertx.VertxMycatServer.MycatSessionManager.class));

        org.mockito.Mockito.reset(socket);
        when(socket.write(any(Buffer.class))).thenReturn(io.vertx.core.Future.succeededFuture());

        int clientFlag = MySQLServerCapabilityFlags.CLIENT_PROTOCOL_41
                | MySQLServerCapabilityFlags.CLIENT_SECURE_CONNECTION; // 无 CLIENT_SSL
        // 用一个最小合法 HandshakeResponse 前缀（含用户名），避免解析异常；关键是首 4 字节不含 CLIENT_SSL。
        Buffer payload = Buffer.buffer();
        payload.appendBytes(le(clientFlag));
        payload.appendBytes(le(16777216));
        payload.appendByte((byte) 45);
        payload.appendBytes(new byte[23]);
        payload.appendBytes(new byte[] { 0 }); // username NUL（放行路径 authenticator==null，用户名内容不校验）
        Buffer packet = frame(payload, 1);

        try {
            handler.handle(packet);
        } catch (RuntimeException expected) {
            // 后续会话装配可能因缺依赖抛出；本用例只关心"未请求 TLS 升级"这一决策。
        }

        verify(socket, never()).upgradeToSsl(any(Handler.class));
    }
}
