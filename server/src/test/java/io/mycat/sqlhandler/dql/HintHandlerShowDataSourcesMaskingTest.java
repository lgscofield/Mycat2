/*
 * I-DEBT-88 离线锁：showDataSources 凭据脱敏。
 *
 * 活体 RED 证据（修复前）：audit/2026-11-08-capability-audit/logs/P-D-01.log ——
 * `/*+ mycat:showDataSources{} *`/ 结果行明文回显后端密码（prototypeDs / audit / Audit#2026）。
 * 本锁钉两件事：
 *  1) maskPassword 对任意非空口令输出固定掩码（不泄露长度/内容）；
 *  2) 形状锁：null 进 null 出（列型/单元格形状不变），非空输入的输出永不等于输入。
 */
package io.mycat.sqlhandler.dql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class HintHandlerShowDataSourcesMaskingTest {

    @Test
    public void nonBlankPasswordIsFullyMasked() {
        assertEquals("******", HintHandler.maskPassword("Audit#2026"));
        assertEquals("******", HintHandler.maskPassword("123456"));
        assertEquals("******", HintHandler.maskPassword("a"));
        assertEquals("******", HintHandler.maskPassword("  spaced secret  "));
    }

    @Test
    public void nullPasswordStaysNull() {
        assertNull(HintHandler.maskPassword(null));
    }

    @Test
    public void blankPasswordIsStillMasked() {
        // 空串也可能是有语义的口令形态（如禁用口令），一概掩码，保持输出与输入无信息等价
        assertEquals("******", HintHandler.maskPassword(""));
    }

    @Test
    public void outputNeverEqualsNonEmptyInput() {
        for (String raw : new String[]{"x", "Audit#2026", "p@ssw0rd-with-长度"}) {
            String masked = HintHandler.maskPassword(raw);
            assertNotEquals(raw, masked);
            assertEquals("******", masked);
        }
    }
}
