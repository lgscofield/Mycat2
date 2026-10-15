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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.IdentityHashMap;
import java.util.Map;

import javax.net.ssl.SSLSession;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import cn.mycat.vertx.xa.MySQLManager;
import cn.mycat.vertx.xa.XaLog;
import io.mycat.MetaClusterCurrent;
import io.mycat.config.ServerConfig;
import io.mycat.config.ServerPersona;
import io.mycat.config.TlsMode;
import io.mycat.config.VersionProfile;
import io.mycat.config.VersionProfileFactory;
import io.mycat.runtime.MycatDataContextImpl;
import io.vertx.core.net.NetSocket;

/**
 * I-DEBT-47 回归锁（回填半）：STARTTLS 升级成功后，{@link VertxMySQLAuthHandler} 必须把前端会话
 * {@link NetSocket#sslSession()} 协商到的 cipher/protocol 落到会话 {@code processStateMap}，供
 * {@code ShowStatusSQLHandler} 回报 Ssl_cipher/Ssl_version。缺此回填，排放侧永远只见到空态。
 */
class FrontendTlsSessionStateCaptureTest {

    private Map<Class, Object> savedContext;

    @BeforeEach
    void setUp() {
        savedContext = MetaClusterCurrent.copyContext();
        VersionProfile profile = VersionProfileFactory.of(ServerPersona.MYSQL_8_0, null, TlsMode.REQUIRED);
        IdentityHashMap<Class, Object> ctx = new IdentityHashMap<>();
        ctx.put(VersionProfile.class, profile);
        ctx.put(XaLog.class, mock(XaLog.class));
        ctx.put(MySQLManager.class, mock(MySQLManager.class));
        ctx.put(ServerConfig.class, new ServerConfig());
        MetaClusterCurrent.register(ctx);
    }

    @AfterEach
    void tearDown() {
        MetaClusterCurrent.register(savedContext);
    }

    /** 握手已完成（sslSession 非空）：应把 cipher/protocol 回填进会话态。 */
    @Test
    void recordsCipherAndVersionFromSslSession() {
        NetSocket socket = mock(NetSocket.class);
        SSLSession ssl = mock(SSLSession.class);
        when(ssl.getCipherSuite()).thenReturn("TLS_AES_256_GCM_SHA384");
        when(ssl.getProtocol()).thenReturn("TLSv1.3");
        when(socket.sslSession()).thenReturn(ssl);

        MycatDataContextImpl dataContext = new MycatDataContextImpl();
        VertxMySQLAuthHandler.recordSslSessionState(socket, dataContext);

        Map<String, Object> state = dataContext.getProcessStateMap();
        assertEquals("TLS_AES_256_GCM_SHA384", state.get("SSL_CIPHER"));
        assertEquals("TLSv1.3", state.get("SSL_VERSION"));
    }

    /** sslSession 尚不可用（异常/空）：不写态、不抛出（降级为排放侧报空串，绝不拖垮认证）。 */
    @Test
    void doesNotThrowWhenSslSessionUnavailable() {
        NetSocket socket = mock(NetSocket.class);
        when(socket.sslSession()).thenReturn(null);

        MycatDataContextImpl dataContext = new MycatDataContextImpl();
        VertxMySQLAuthHandler.recordSslSessionState(socket, dataContext);

        assertTrue(!dataContext.getProcessStateMap().containsKey("SSL_CIPHER"),
                "无 sslSession 时不应写入 SSL_CIPHER");
    }
}
