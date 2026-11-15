/*
 * I-DEBT-124 离线锁：isManagementHint 管理面覆盖矩阵。
 *
 * 修复前清单只收 12 个写集群/数据移动面 cmd，缺口（本锁逐项钉死，非 admin 一律拒）：
 *  兜底 mycatDmlHandler 可达：createTable/dropTable/createSchema/dropSchema/setSequence/
 *                            repairPhysicalTable/readXARecoveryLog
 *  配置缓存写面：createSqlCache/dropSqlCache；用户方言写面：setUserDialect
 *  binlog 任务控制：BINLOG_STOP/BINLOG_CLEAR/BINLOG_SYNC
 *  线程/服务控制：killThread/interruptThread/setAcceptConnect/pauseServer/resumeServer
 *  执行器开关（全局静态）：setBkaJoin/setSortMergeJoin/setBkaJoinLeftRowCountLimit
 * 对照组：诊断类 show* 与只读/路由类（baseline/run/MIGRATE_LIST/BINLOG_LIST/...）不在门内，
 * 不得误伤既有开放清单。
 */
package io.mycat.sqlhandler.dql;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HintHandlerManagementGateCoverageTest {

    private static final List<String> MUST_BE_GATED = Arrays.asList(
            // I-DEBT-121/125 并入
            "showConfigText", "loaddata",
            // 兜底 mycatDmlHandler 可达
            "createTable", "dropTable", "createSchema", "dropSchema", "setSequence",
            "repairPhysicalTable", "readXARecoveryLog",
            // 配置缓存 / 用户方言
            "createSqlCache", "dropSqlCache", "setUserDialect",
            // binlog 任务控制
            "BINLOG_STOP", "BINLOG_CLEAR", "BINLOG_SYNC",
            // 线程 / 服务控制
            "killThread", "interruptThread", "setAcceptConnect", "pauseServer", "resumeServer",
            // 执行器全局开关
            "setBkaJoin", "setSortMergeJoin", "setBkaJoinLeftRowCountLimit"
    );

    private static final List<String> MUST_STAY_OPEN = Arrays.asList(
            // 诊断类 show*
            "showDataSources", "showClusters", "showTables", "showSchemas", "showUsers",
            "showSqlCaches", "showSlowSql", "showTopology", "showErGroup", "showThreadInfo",
            "showBufferUsage", "showConnections", "showBackends",
            // 只读/路由/会话级
            "baseline", "run", "MIGRATE_LIST", "BINLOG_LIST", "BINLOG_SNAPSHOT",
            "checkConfigConsistency", "setDebug", "setVector", "is", "setReadyToCloseSQL"
    );

    @Test
    public void everyManagementCommandIsGated() {
        for (String cmd : MUST_BE_GATED) {
            assertTrue(HintHandler.isManagementHint(cmd),
                    "管理面 cmd 必须入 admin 门: " + cmd);
        }
    }

    @Test
    public void diagnosticsAndRoutingStayOpen() {
        for (String cmd : MUST_STAY_OPEN) {
            assertFalse(HintHandler.isManagementHint(cmd),
                    "诊断/路由类 cmd 不得误伤: " + cmd);
        }
    }

    @Test
    public void gateIsCaseInsensitiveOnCanonicalCommands() {
        assertTrue(HintHandler.isManagementHint("createtable"));
        assertTrue(HintHandler.isManagementHint("CREATEsqlCACHE"));
        assertTrue(HintHandler.isManagementHint("binlog_stop"));
    }

    @Test
    public void nullAndUnknownAreNotGated() {
        assertFalse(HintHandler.isManagementHint(null));
        assertFalse(HintHandler.isManagementHint(""));
        assertFalse(HintHandler.isManagementHint("unknownCmd"));
    }
}
