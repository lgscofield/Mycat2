package io.mycat;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlShowMasterStatusStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlShowSlaveStatusStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlShowStatement;
import io.mycat.sql.MycatSQLUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * I-ME-4 · SHOW MASTER/SLAVE STATUS 透传路径回归锁。
 * <p>
 * Done 门锚定：mysqldump --source-data 发送 SHOW MASTER STATUS，
 * Mycat 透传至 prototype（{@code MycatdbCommand L497-499} fallback 分支），
 * 本锁验证<b>解析→类型识别→MySqlShowStatement 接口标记→文本转发</b>四环节正确性。
 * <p>
 * 官网依据（非臆测）：
 * <ul>
 *   <li>MySQL 8.0 §15.7.7.23 SHOW MASTER STATUS（输出 File/Position/Binlog_Do_DB/Binlog_Ignore_DB/Executed_Gtid_Set）</li>
 *   <li>MySQL 8.0 §15.7.7.36 SHOW SLAVE|REPLICA STATUS [FOR CHANNEL channel]（8.0.22+ 旧语法仍可用）</li>
 *   <li>MariaDB SHOW MASTER/SLAVE STATUS（无 FOR CHANNEL 变体，列面与 MySQL 略有差异）</li>
 * </ul>
 * e2e 真跑覆盖见 {@code example} 侧 DumpSurfaceMatrixTest 已含 SHOW MASTER STATUS 格（I-ME-2 建格，待 infra）。
 */
public class ShowBinlogStatusRoutingTest {

    // ===== 1. 解析→正确 AST 类型 =====

    @Test
    public void showMasterStatusParsesToCorrectAstType() {
        SQLStatement stmt = SQLUtils.parseSingleMysqlStatement("SHOW MASTER STATUS");
        assertInstanceOf(MySqlShowMasterStatusStatement.class, stmt,
                "SHOW MASTER STATUS 必须解析为 MySqlShowMasterStatusStatement");
    }

    @Test
    public void showSlaveStatusParsesToCorrectAstType() {
        SQLStatement stmt = SQLUtils.parseSingleMysqlStatement("SHOW SLAVE STATUS");
        assertInstanceOf(MySqlShowSlaveStatusStatement.class, stmt,
                "SHOW SLAVE STATUS 必须解析为 MySqlShowSlaveStatusStatement");
    }

    // ===== 2. MySqlShowStatement 接口标记（MycatdbCommand L497 路由判据） =====

    @Test
    public void showMasterStatusImplementsMySqlShowStatementForFallbackRouting() {
        SQLStatement stmt = SQLUtils.parseSingleMysqlStatement("SHOW MASTER STATUS");
        // MycatdbCommand L497: if (sqlStatement instanceof MySqlShowStatement) → proxySelectToPrototype
        assertTrue(stmt instanceof MySqlShowStatement,
                "必须实现 MySqlShowStatement 以触发 MycatdbCommand fallback 透传");
    }

    @Test
    public void showSlaveStatusImplementsMySqlShowStatementForFallbackRouting() {
        SQLStatement stmt = SQLUtils.parseSingleMysqlStatement("SHOW SLAVE STATUS");
        assertTrue(stmt instanceof MySqlShowStatement,
                "必须实现 MySqlShowStatement 以触发 MycatdbCommand fallback 透传");
    }

    // ===== 3. toString() 产生可转发的合法 SQL 文本（proxySelectToPrototype 入参） =====

    @Test
    public void showMasterStatusDruidToStringProducesValidForwardableSql() {
        SQLStatement stmt = SQLUtils.parseSingleMysqlStatement("SHOW MASTER STATUS");
        // MycatdbCommand L499: proxySelectToPrototype(sqlStatement.toString(), ...)
        String sql = stmt.toString();
        assertNotNull(sql);
        assertTrue(sql.toUpperCase().contains("SHOW") && sql.toUpperCase().contains("MASTER STATUS"),
                "druid toString() 必须产出可转发 SQL，got: " + sql);
    }

    @Test
    public void showSlaveStatusDruidToStringProducesValidForwardableSql() {
        SQLStatement stmt = SQLUtils.parseSingleMysqlStatement("SHOW SLAVE STATUS");
        String sql = stmt.toString();
        assertNotNull(sql);
        assertTrue(sql.toUpperCase().contains("SHOW") && sql.toUpperCase().contains("SLAVE STATUS"),
                "druid toString() 必须产出可转发 SQL，got: " + sql);
    }

    // ===== 4. MycatOutputVisitor 渲染文本正确（MycatSQLUtils.toString 用于 toUnformattedString 路径） =====

    @Test
    public void mycatVisitorRendersShowMasterStatusCorrectly() {
        SQLStatement stmt = SQLUtils.parseSingleMysqlStatement("SHOW MASTER STATUS");
        String rendered = MycatSQLUtils.toString(stmt);
        assertEquals("SHOW MASTER STATUS", rendered.toUpperCase().trim());
    }

    @Test
    public void mycatVisitorRendersShowSlaveStatusCorrectly() {
        SQLStatement stmt = SQLUtils.parseSingleMysqlStatement("SHOW SLAVE STATUS");
        String rendered = MycatSQLUtils.toString(stmt);
        assertEquals("SHOW SLAVE STATUS", rendered.toUpperCase().trim());
    }

    // ===== 5. 大小写/空格鲁棒性（实际客户端可能发送小写或混合格式） =====

    @Test
    public void lowercaseShowMasterStatusStillRoutesCorrectly() {
        SQLStatement stmt = SQLUtils.parseSingleMysqlStatement("show master status");
        assertInstanceOf(MySqlShowMasterStatusStatement.class, stmt);
        assertTrue(stmt instanceof MySqlShowStatement);
    }

    @Test
    public void lowercaseShowSlaveStatusStillRoutesCorrectly() {
        SQLStatement stmt = SQLUtils.parseSingleMysqlStatement("show slave status");
        assertInstanceOf(MySqlShowSlaveStatusStatement.class, stmt);
        assertTrue(stmt instanceof MySqlShowStatement);
    }

    // ===== 6. I-DEBT-53：MySQL 8.4 改名后的复制状态 SHOW，druid 解析层即拒绝 =====
    // 钉住依赖现状：改名命令无 AST，故 Mycat 侧必须靠文本分类器透传（见
    // MycatdbCommandShowSalvageTest）。若未来 druid 升级后能解析这两条，
    // 本锁会红，提醒重新评估是否可回归 AST 路径、移除文本分类器。

    @Test
    public void druidRejectsShowBinaryLogStatus() {
        assertThrows(com.alibaba.druid.sql.parser.ParserException.class,
                () -> SQLUtils.parseSingleMysqlStatement("SHOW BINARY LOG STATUS"),
                "SHOW BINARY LOG STATUS 当前不被 druid 解析（I-DEBT-53 透传前提）");
    }

    @Test
    public void druidRejectsShowReplicaStatus() {
        assertThrows(com.alibaba.druid.sql.parser.ParserException.class,
                () -> SQLUtils.parseSingleMysqlStatement("SHOW REPLICA STATUS"),
                "SHOW REPLICA STATUS 当前不被 druid 解析（I-DEBT-53 透传前提）");
    }
}
