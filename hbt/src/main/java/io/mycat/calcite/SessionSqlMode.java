package io.mycat.calcite;

/**
 * MYCAT CHANGE (D2b 第一刀): 每查询/每会话的 ONLY_FULL_GROUP_BY 生效态持有者。
 *
 * <p>Calcite 的分组校验（{@code AggChecker}）历史上恒严格，与 MySQL 按会话
 * {@code sql_mode} 动态开关 ONLY_FULL_GROUP_BY 的语义天然错位：关闭该模式的存量会话
 * （生产常见，含 mysqldump 发出的 {@code SET @@SQL_MODE=''}）里合法的非严格 GROUP BY 查询，
 * 迁到 Mycat 跨片路径会被误拒。按生态原则「代理不应比后端严」，本持有者把会话 sql_mode 是否含
 * ONLY_FULL_GROUP_BY 穿进 validator 出生点（{@link MycatCalciteSupport#getValidatorConfig()}），
 * 由 {@code SqlValidator.Config.onlyFullGroupBy()} 承载，最终门控 {@code AggChecker} 的 notGroupExpr。
 *
 * <p>沿用 {@code ParamHolder.CURRENT_THREAD_LOCAL} 的既有惯例：编译/校验在同一线程执行，
 * 用 ThreadLocal 携带 per-query 上下文。缺省 {@code true}（严格）——即未显式穿会话态时
 * （离线测、golden 基线、非代理入口路径）保持 Calcite 原生严格行为，零回归；仅当代理入口
 * 依据会话 sql_mode 显式置 false 时才放宽。
 */
public final class SessionSqlMode {

    private SessionSqlMode() {
    }

    /** 当前线程本查询的 ONLY_FULL_GROUP_BY 生效态；缺省严格。 */
    private static final ThreadLocal<Boolean> ONLY_FULL_GROUP_BY =
            ThreadLocal.withInitial(() -> Boolean.TRUE);

    /** 返回当前查询是否应强制 ONLY_FULL_GROUP_BY（缺省 true=严格）。 */
    public static boolean isOnlyFullGroupBy() {
        return ONLY_FULL_GROUP_BY.get();
    }

    /** 由代理查询入口按会话 sql_mode 设定（含 ONLY_FULL_GROUP_BY→true，否则 false）。 */
    public static void setOnlyFullGroupBy(boolean onlyFullGroupBy) {
        ONLY_FULL_GROUP_BY.set(onlyFullGroupBy);
    }

    /** 查询结束复位为缺省严格，避免线程复用（尤其虚拟线程/池化）串味。 */
    public static void reset() {
        ONLY_FULL_GROUP_BY.remove();
    }
}
