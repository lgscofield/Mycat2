/**
 * Copyright (C) <2021>  <chen junwen>
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package io.mycat.sqlhandler.dql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlShowStatusStatement;

import cn.mycat.vertx.xa.MySQLManager;
import cn.mycat.vertx.xa.XaLog;
import io.mycat.MetaClusterCurrent;
import io.mycat.Response;
import io.mycat.api.collector.RowBaseIterator;
import io.mycat.config.ServerConfig;
import io.mycat.config.ServerPersona;
import io.mycat.config.TlsMode;
import io.mycat.config.VersionProfile;
import io.mycat.config.VersionProfileFactory;
import io.mycat.runtime.MycatDataContextImpl;
import io.mycat.sqlhandler.SQLRequest;
import io.vertx.core.Future;

/**
 * I-DEBT-47 回归锁：{@code SHOW SESSION STATUS LIKE 'Ssl_cipher'} 必须回报「客户端↔Mycat」前端 TLS 会话
 * 协商到的 cipher，而非恒空。旧实现 {@link ShowStatusSQLHandler} 对所有 SHOW STATUS 一律返回空行集，
 * 使依赖 cipher 判定会话是否加密的客户端/探针误判为明文。
 *
 * <p>本用例把"排放"这一半独立钉死：会话 TLS 态（{@code processStateMap} 里的 SSL_CIPHER/SSL_VERSION，
 * 由 {@code VertxMySQLAuthHandler} 在 STARTTLS 升级成功后回填）存在时，handler 应据此产出行并尊重 LIKE。
 */
class ShowStatusSSLTest {

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

    /** 驱动 handler，捕获送出的结果集并读成 List<Map>。 */
    private List<Map<String, Object>> runShowStatus(String sql, MycatDataContextImpl ctx) {
        MySqlShowStatusStatement ast =
                (MySqlShowStatusStatement) SQLUtils.parseSingleStatement(sql, DbType.mysql);
        Response response = mock(Response.class);
        final RowBaseIterator[] captured = new RowBaseIterator[1];
        when(response.sendResultSet(any(RowBaseIterator.class))).thenAnswer(inv -> {
            captured[0] = inv.getArgument(0);
            return Future.succeededFuture();
        });
        new ShowStatusSQLHandler().execute(new SQLRequest<>(ast), ctx, response);
        assertTrue(captured[0] != null, "handler 应送出结果集");
        return captured[0].getResultSetMap();
    }

    /** TLS 会话：SHOW SESSION STATUS LIKE 'Ssl_cipher' 应回报实际 cipher。 */
    @Test
    void tlsSessionReportsSslCipher() {
        MycatDataContextImpl ctx = new MycatDataContextImpl();
        ctx.getProcessStateMap().put("SSL_CIPHER", "TLS_AES_256_GCM_SHA384");
        ctx.getProcessStateMap().put("SSL_VERSION", "TLSv1.3");

        List<Map<String, Object>> rows = runShowStatus("SHOW SESSION STATUS LIKE 'Ssl_cipher'", ctx);

        Map<String, Object> cipher = rows.stream()
                .filter(r -> "Ssl_cipher".equalsIgnoreCase(String.valueOf(r.get("Variable_name"))))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "TLS 会话下 SHOW SESSION STATUS 应含 Ssl_cipher 行，实得 " + rows));
        assertEquals("TLS_AES_256_GCM_SHA384", cipher.get("Value"));
    }

    /** 非 TLS 会话：Ssl_cipher 行仍存在但值为空串（与 MySQL 一致）。 */
    @Test
    void plainSessionReportsEmptySslCipherRow() {
        MycatDataContextImpl ctx = new MycatDataContextImpl();
        List<Map<String, Object>> rows = runShowStatus("SHOW SESSION STATUS LIKE 'Ssl_cipher'", ctx);
        Map<String, Object> cipher = rows.stream()
                .filter(r -> "Ssl_cipher".equalsIgnoreCase(String.valueOf(r.get("Variable_name"))))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "非 TLS 会话仍应有 Ssl_cipher 空值行，实得 " + rows));
        assertEquals("", cipher.get("Value"));
    }

    /** LIKE 'Ssl%' 前缀匹配：应含 Ssl_cipher 与 Ssl_version 两行。 */
    @Test
    void likePrefixMatchesBothSslVars() {
        MycatDataContextImpl ctx = new MycatDataContextImpl();
        ctx.getProcessStateMap().put("SSL_CIPHER", "ECDHE-RSA-AES256-GCM-SHA384");
        ctx.getProcessStateMap().put("SSL_VERSION", "TLSv1.2");

        List<Map<String, Object>> rows = runShowStatus("SHOW SESSION STATUS LIKE 'Ssl%'", ctx);
        List<String> names = rows.stream().map(r -> String.valueOf(r.get("Variable_name"))).toList();
        assertTrue(names.contains("Ssl_cipher"), "应含 Ssl_cipher：" + names);
        assertTrue(names.contains("Ssl_version"), "应含 Ssl_version：" + names);
        assertEquals("ECDHE-RSA-AES256-GCM-SHA384", rows.stream()
                .filter(r -> "Ssl_cipher".equalsIgnoreCase(String.valueOf(r.get("Variable_name"))))
                .findFirst().orElseThrow().get("Value"));
    }
}
