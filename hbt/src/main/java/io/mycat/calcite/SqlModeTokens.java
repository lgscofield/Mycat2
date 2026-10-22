package io.mycat.calcite;

import java.util.Locale;

/**
 * I-DEBT-54 D2b-B：从会话 sql_mode 原始串判定是否含 ONLY_FULL_GROUP_BY。
 * 纯函数，token 级精确匹配（逗号/空白切分 + 大写），不做子串匹配，避免相似名误判。
 */
public final class SqlModeTokens {

    private static final String ONLY_FULL_GROUP_BY = "ONLY_FULL_GROUP_BY";

    private SqlModeTokens() {
    }

    /**
     * @param sqlModeRaw 会话 sql_mode 原始串（逗号/空白分隔的模式列表）。
     * @return null 视为 MySQL 缺省（严格）返回 true；空串或无命中返回 false；
     *         含 ONLY_FULL_GROUP_BY token（大小写不敏感）返回 true。
     */
    public static boolean containsOnlyFullGroupBy(String sqlModeRaw) {
        if (sqlModeRaw == null) {
            // 未捕获到会话 sql_mode：沿用 MySQL 默认严格语义。
            return true;
        }
        for (String token : sqlModeRaw.split("[,\\s]+")) {
            if (ONLY_FULL_GROUP_BY.equals(token.trim().toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
