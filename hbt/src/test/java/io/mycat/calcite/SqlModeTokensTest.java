package io.mycat.calcite;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * I-DEBT-54 D2b-B：sql_mode 原始串 → ONLY_FULL_GROUP_BY token 级判定（纯函数）。
 * 判据：逗号/空白切分后大写精确匹配 token；null=缺省严格 true；空串=关闭全部 false；
 * 相似名/子串不得误判。
 */
class SqlModeTokensTest {

    @Test
    void containsWhenPresentInList() {
        Assertions.assertTrue(SqlModeTokens.containsOnlyFullGroupBy("ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES"));
    }

    @Test
    void absentNotFalsedBySubstring() {
        Assertions.assertFalse(SqlModeTokens.containsOnlyFullGroupBy("STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION"));
    }

    @Test
    void emptyIsOffNullIsDefaultStrict() {
        Assertions.assertFalse(SqlModeTokens.containsOnlyFullGroupBy(""));
        Assertions.assertTrue(SqlModeTokens.containsOnlyFullGroupBy(null));
    }

    @Test
    void caseInsensitive() {
        Assertions.assertTrue(SqlModeTokens.containsOnlyFullGroupBy("only_full_group_by"));
    }

    @Test
    void mixedWhitespaceAndCommaDelimiters() {
        Assertions.assertTrue(SqlModeTokens.containsOnlyFullGroupBy("  ONLY_FULL_GROUP_BY , STRICT_TRANS_TABLES "));
    }
}
