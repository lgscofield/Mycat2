package io.mycat.runtime;

import cn.mycat.vertx.xa.SavepointSqlConnection;
import cn.mycat.vertx.xa.impl.BaseXaSqlConnection;
import io.mycat.MetaClusterCurrent;
import io.mycat.beans.mycat.TransactionType;
import io.mycat.config.ServerConfig;
import io.mycat.protocol.mysql.type.MySQLIsolation;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * I-DEBT-84 Phase2 阶段2：生产会话装饰链 {@code MycatXaTranscation → SavepointSqlConnection → BaseXaSqlConnection}
 * 的 commit 决定/超时四标志委托锁。
 *
 * <p>{@code ReceiverImpl.withCommitLegDeadline} 在 {@code transactionSession}（即 MycatXaTranscation）上调
 * {@code markCommitTimedOut()}（R2：va retryCommit 让位 reaper）与 {@code isCommitOutcomeUnknown()}（R3：已决超时
 * 文案追加 outcome unknown）。若外层装饰器吞掉委托、落 {@code XaSqlConnection} 的 no-op 默认，则 XA 生产路径上
 * R2/R3 静默失效。既有 va/server 单测直接驱动内层或 mock，未穿过本装饰器，故补此锁钉住委托贯通。
 *
 * <p>用真 {@link BaseXaSqlConnection}（携带 commitDecided/commitTimedOut volatile 真态）作内层，经生产同款
 * {@link SavepointSqlConnection} 包裹，断言标志穿透整条装饰链——非 mock 行为，乃真实状态传播。
 */
class MycatXaTranscationCommitFlagDelegationTest {

    @BeforeAll
    static void registerServerConfig() {
        // MycatXaTranscation 构造期 wrapper(ServerConfig.class) 要求非空；显式关 sticky 以走非 sticky 亲近性分支。
        ServerConfig serverConfig = new ServerConfig();
        serverConfig.setStickySession(false);
        MetaClusterCurrent.register(ServerConfig.class, serverConfig);
    }

    private MycatXaTranscation newSession() {
        BaseXaSqlConnection inner = new BaseXaSqlConnection(MySQLIsolation.DEFAULT, () -> null, null);
        return new MycatXaTranscation(new SavepointSqlConnection(inner), TransactionType.JDBC_TRANSACTION_TYPE);
    }

    @Test
    void markCommitDecidedDelegatesThroughChain() {
        MycatXaTranscation session = newSession();
        Assertions.assertFalse(session.isCommitDecided(), "初始未决");
        session.markCommitDecided();
        Assertions.assertTrue(session.isCommitDecided(),
                "markCommitDecided 须穿透装饰链置内层 commitDecided（R1 决定点归位）");
    }

    @Test
    void commitOutcomeUnknownRequiresBothFlagsThroughChain() {
        MycatXaTranscation session = newSession();
        // 仅超时、未决 ⇒ outcome 非 unknown（单 DS/未决维持标准 3013 文案）。
        session.markCommitTimedOut();
        Assertions.assertFalse(session.isCommitOutcomeUnknown(), "未决超时 outcome 不应 unknown");
        // 决定点已过 + 超时 ⇒ outcome unknown（R3 文案追加 outcome unknown; recovery in background）。
        session.markCommitDecided();
        Assertions.assertTrue(session.isCommitOutcomeUnknown(),
                "已决+超时须穿透装饰链令 isCommitOutcomeUnknown=true（R3）");
    }
}