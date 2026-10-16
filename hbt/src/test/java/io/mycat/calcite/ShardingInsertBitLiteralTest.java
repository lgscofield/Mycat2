package io.mycat.calcite;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.visitor.ParameterizedOutputVisitorUtils;
import io.mycat.calcite.executor.MycatPreparedStatementUtil;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * I-DEBT-44 回归锁：多行 VALUES 里各行的 BIT 字面量必须经"参数化→还原"往返后各自保真。
 *
 * <p>根因：分片 INSERT（{@code UpdateSQLHandler} 对 SHARDING/CUSTOM/VISUAL/VIEW 走
 * {@link DrdsRunnerHelper#preParse}）使用通用
 * {@link MycatPreparedStatementUtil#outputToParameterized(SQLStatement, String, StringBuilder, List, List, List, MutableBoolean)}。
 * 该方法未启用 druid 的 {@code OutputParameterizedQuesUnMergeValuesList}，于是多行 VALUES 被合并去重：
 * 只把可参数化的列（如 id）抽为 {@code ?}，把<b>不被参数化</b>的 bit 字面量 {@code b'...'} 烤进共享模板，
 * 其余行的不同 bit 值被沉默丢弃——还原后所有行都用首行的 bit 值，写库即数据损坏。
 *
 * <p>对照：普通/全局 INSERT 走 {@code outputToParameterizedProxySql}（已启用不合并）——不损坏。
 * 本锁钉住"分片路径同样不得合并塌缩"这一收口。
 */
public class ShardingInsertBitLiteralTest {

    private static String parameterizeAndRestore(String sql) {
        SQLStatement stmt = SQLUtils.parseSingleMysqlStatement(sql);
        StringBuilder sb = new StringBuilder();
        List<Object> params = new ArrayList<>();
        MycatPreparedStatementUtil.outputToParameterized(stmt, null, sb, null, params,
                new LinkedList<>(), new MutableBoolean());
        return ParameterizedOutputVisitorUtils.restore(sb.toString(), DbType.mysql, params);
    }

    @Test
    public void multiRowBitLiteralsSurviveGeneralParameterization() {
        // 核心不变式：两行不同的 bit 字面量都必须各自保真——第二行的 FF 绝不能被折成首行的 AA。
        String restored = parameterizeAndRestore(
                "insert into t_bit(`id`,`c`) values (2, b'10101010'),(3, b'11111111')");

        Assertions.assertTrue(restored.contains("b'10101010'"),
                "第一行 bit 必须保真: " + restored);
        Assertions.assertTrue(restored.contains("b'11111111'"),
                "第二行 bit 不得被 VALUES 合并吞掉（I-DEBT-44 塌缩为 1）: " + restored);
    }

    @Test
    public void ordinaryMultiRowValuesStillRoundTrip() {
        // 守卫：关闭 VALUES 合并不得破坏普通多行 INSERT 的往返（各行都在、参数保序）。
        String restored = parameterizeAndRestore(
                "insert into t(`id`,`name`) values (1,'a'),(2,'b')");

        Assertions.assertTrue(restored.contains("'a'") && restored.contains("'b'"),
                "两行数据都须保真: " + restored);
        Assertions.assertTrue(restored.contains("1") && restored.contains("2"),
                "两行主键都须保真: " + restored);
    }
}
