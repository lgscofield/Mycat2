/*
 * I-DEBT-86 刀B扩展 离线锁：净室（无 prototype 拉取）下 session 变量继承 global 缺省。
 *
 * 活体 RED 证据（修复前）：audit/2026-11-08-capability-audit/logs/V-86-cleanroom.log ——
 * 净室 boot 下 `@@session.auto_increment_increment` 返 null（修复前整批引导查询直接崩，
 * 刀A/B 后可连但 ~16 个引导变量仍为 null 诚实降级）。本锁钉继承语义：
 *  1) session 缺失的键从 global 继承（Connector/J 引导查询 22 变量面）；
 *  2) session 已有的值绝不被 global 覆写（会话态优先，对齐真库"会话建立时自 global 继承"）；
 *  3) global 也没有的键保持缺失（不造值）。
 */
package io.mycat;

import io.mycat.protocol.codec.NameMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class MysqlVariableServiceImplCleanroomSeedTest {

    @Test
    public void sessionInheritsMissingKeysFromGlobal() {
        NameMap<Object> global = new NameMap<>();
        NameMap<Object> session = new NameMap<>();
        global.put("auto_increment_increment", "1");
        global.put("wait_timeout", "172800");
        MysqlVariableServiceImpl.seedSessionFromGlobal(global, session);
        assertEquals("1", session.get("auto_increment_increment", false));
        assertEquals("172800", session.get("wait_timeout", false));
    }

    @Test
    public void existingSessionValuesAreNeverOverwritten() {
        NameMap<Object> global = new NameMap<>();
        NameMap<Object> session = new NameMap<>();
        global.put("sql_mode", "STRICT_TRANS_TABLES");
        session.put("sql_mode", "ALLOW_INVALID_DATES");
        MysqlVariableServiceImpl.seedSessionFromGlobal(global, session);
        assertEquals("ALLOW_INVALID_DATES", session.get("sql_mode", false));
    }

    @Test
    public void keysMissingInBothStayMissing() {
        NameMap<Object> global = new NameMap<>();
        NameMap<Object> session = new NameMap<>();
        global.put("license", "GPL");
        MysqlVariableServiceImpl.seedSessionFromGlobal(global, session);
        assertEquals("GPL", session.get("license", false));
        assertNull(session.get("not_a_variable", false));
    }
}
