package io.mycat.calcite;

import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.mycat.DrdsSqlWithParams;

/**
 * I-DEBT-29 元数据 GUI / DBeaver 面离线回归锁（spec
 * {@code docs/superpowers/specs/2026-10-30-metadata-gui-dbeaver-design.md} §6 T2/T3）。
 *
 * <p>纯离线、秒级、不需 Docker / 8066：钉死 druid 1.2.28 reprint 吞相邻 {@code @@} 系统变量
 * 之间分隔逗号、我方 {@link DrdsRunnerHelper#preParse} 再解析自咬抛
 * {@code ParserException: token VARIANT} 这一确定性缺陷。
 *
 * <p>判失效面用 {@link DrdsSqlWithParams#getParameterizedStatement()}：它对 reprint 出来的
 * 参数化 SQL 文本二次 {@code parseSingleMysqlStatement}，丢逗号形态必抛 ParserException——
 * 这正是活链路（{@code SqlResultSetService}/{@code MycatdbCommand}）崩溃的那一步。
 *
 * <p>RED-first：修复前下标 &gt;0 为 {@code SQLVariantRefExpr}（作用域名 {@code @@global.x}/{@code @@x}
 * 走三参构造、{@code hasPrefixComma=false}）的形态全红；修法 A（preParse 遍历 AST 置
 * {@code setHasPrefixComma(true)}）后 12/12 + WHERE 守卫全绿。
 */
public class VariantReprintFidelityTest {

    /**
     * §4.2 的 12 例变体矩阵。每条：{输入 SQL, 修复后期望的参数化文本（null=不锁字面，仅锁可再解析）}。
     * 字面期望锚定当前 druid toString 的归一形态（@@GLOBAL→@@global、逗号后单空格）。
     */
    static Stream<org.junit.jupiter.params.provider.Arguments> matrix() {
        return Stream.of(
                // 1 单列 global：下标 0 无前置逗号，本就 OK
                org.junit.jupiter.params.provider.Arguments.of("SELECT @@GLOBAL.character_set_server", null),
                // 2 裸 @@x,@@y：两项一参构造 true，逗号在
                org.junit.jupiter.params.provider.Arguments.of("SELECT @@character_set_server,@@collation_server", null),
                // 3 global + 裸：[1] 裸 true，逗号在
                org.junit.jupiter.params.provider.Arguments.of("SELECT @@GLOBAL.x, @@collation_server", null),
                // 4 裸 + global：[1] global=false-prefix → 吞（RED）
                org.junit.jupiter.params.provider.Arguments.of("SELECT @@character_set_server,@@GLOBAL.collation_server", null),
                // 5 global + 整型字面量：[1] SQLIntegerExpr，逗号在
                org.junit.jupiter.params.provider.Arguments.of("SELECT @@GLOBAL.x, 1", null),
                // 6 整型 + global：[1] global → 吞（RED）
                org.junit.jupiter.params.provider.Arguments.of("SELECT 1, @@GLOBAL.collation_server", null),
                // 7 session + session：两项 SQLPropertyExpr，永久免疫
                org.junit.jupiter.params.provider.Arguments.of("SELECT @@session.sql_mode, @@session.autocommit", null),
                // 8 global + global：[1] global → 吞（RED）
                org.junit.jupiter.params.provider.Arguments.of("SELECT @@global.max_allowed_packet, @@global.character_set_database", null),
                // 9 DBeaver 招牌那条（=离线 ClientTest 断言本体），锁字面
                org.junit.jupiter.params.provider.Arguments.of("SELECT @@global.character_set_server, @@global.collation_server",
                        "SELECT @@global.character_set_server, @@global.collation_server"),
                // 10 global + session：[1] SQLPropertyExpr，逗号在
                org.junit.jupiter.params.provider.Arguments.of("SELECT @@global.a, @@session.b", null),
                // 11 session + global：[1] global → 吞（RED）
                org.junit.jupiter.params.provider.Arguments.of("SELECT @@session.a, @@global.b", null),
                // 12 三枚 global：两处均吞（RED）
                org.junit.jupiter.params.provider.Arguments.of("SELECT @@global.a, @@global.b, @@global.c", null));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("matrix")
    public void reprintedParameterizedSqlMustReparse(String sql, String expectedLiteral) {
        DrdsSqlWithParams parsed = DrdsRunnerHelper.preParse(sql, null);
        String parameterized = parsed.getParameterizedSQL();
        // 失效面主锁：再解析不得抛 ParserException（token VARIANT）。
        Assertions.assertDoesNotThrow(parsed::getParameterizedStatement,
                () -> "reprint 吞逗号致再解析失败：输入=[" + sql + "] 输出=[" + parameterized + "]");
        // 字面锁（仅招牌那条）：修复后应逐字符还原分隔逗号。
        if (expectedLiteral != null) {
            Assertions.assertEquals(expectedLiteral, parameterized);
        }
    }

    /**
     * T3 守卫：非 selectList 位置（WHERE）的 variant。钉「遍历整棵 AST 而非只挑 selectList」的作用域决定。
     * 该形态当前即正常（本例不冒充 RED），作为防回归守卫锁保留。
     */
    @Test
    public void whereClauseVariantStaysReparseable() {
        String sql = "SELECT 1 FROM dual WHERE @@global.sql_mode IS NOT NULL";
        DrdsSqlWithParams parsed = DrdsRunnerHelper.preParse(sql, null);
        Assertions.assertDoesNotThrow(parsed::getParameterizedStatement,
                () -> "WHERE 位 variant 再解析失败：输出=[" + parsed.getParameterizedSQL() + "]");
    }
}
