/*
 * I-DEBT-86 离线锁：净室启动（mycat-home 缺 clusters/）时 session 变量表缺
 * Connector/J 引导查询消费的整型变量，null 进 toInt → parseInt("null") 崩 → 会话死。
 *
 * 活体 RED 证据（修复前）：audit/2026-11-08-capability-audit/logs/P-L1-01.log ——
 * 净室 boot 下任何 JDBC 客户端连 8066 即 `NumberFormatException: For input string: "null"`；
 * boot 日志 L382-405 全栈：MysqlVariableServiceImpl.fixValue:141 → MySQLVariablesUtil.toInt:127。
 *
 * 锁面：fixIntLikeVariable（包级 static，供回归锁直接调用，对齐 I-DEBT-56 先例）——
 *  1) null + 整数族变量（max_allowed_packet/innodb_file_per_table/FOREIGN_KEY_CHECKS）→ 按内置
 *     global 默认表回落，绝不进 toInt；
 *  2) 非空值语义不变（仍走 toInt）；
 *  3) 非整数族变量 null 仍原样返回 null（窄面，不扩大设防范围）。
 */
package io.mycat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class MysqlVariableServiceImplFixValueTest {

    @Test
    public void nullMaxAllowedPacketFallsBackToBuiltinDefault() {
        assertEquals(16777216, MysqlVariableServiceImpl.fixIntLikeVariable("max_allowed_packet", null));
    }

    @Test
    public void nonNullMaxAllowedPacketStillParsed() {
        assertEquals(16777216, MysqlVariableServiceImpl.fixIntLikeVariable("max_allowed_packet", "16777216"));
        assertEquals(67108864, MysqlVariableServiceImpl.fixIntLikeVariable("max_allowed_packet", "67108864"));
    }

    @Test
    public void otherIntLikeVarsFallBackOnNull() {
        assertEquals(1, MysqlVariableServiceImpl.fixIntLikeVariable("innodb_file_per_table", null));
        assertEquals(1, MysqlVariableServiceImpl.fixIntLikeVariable("FOREIGN_KEY_CHECKS", null));
    }

    @Test
    public void nonIntLikeVarsKeepNullPassthrough() {
        assertNull(MysqlVariableServiceImpl.fixIntLikeVariable("some_other_var", null));
        assertEquals("REPEATABLE-READ", MysqlVariableServiceImpl.fixIntLikeVariable("transaction_isolation", "REPEATABLE-READ"));
    }
}
