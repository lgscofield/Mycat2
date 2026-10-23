package io.mycat.calcite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.junit.jupiter.api.Test;

/**
 * I-DEBT-61（跨片 STDDEV/VARIANCE 对齐 MySQL 总体口径）：裸 STDDEV/VARIANCE 算子 kind 校验期锁。
 *
 * <p>跨分片归并 STDDEV/VARIANCE 走本地 {@code AggregateReduceFunctionsRule}，其规约分母（÷N 总体 vs
 * ÷(N-1) 样本）由被规约算子的 {@link SqlKind} 决定（{@code STDDEV_POP/VAR_POP}→biased=总体、
 * {@code STDDEV_SAMP/VAR_SAMP}→biased=样本）。Calcite 上游随 SQL 标准把<b>裸</b> STDDEV/VARIANCE 定成
 * 样本孪生，而 MySQL/MariaDB 无后缀默认返<b>总体</b>——跨片本地归并因此与真库错开（stddev 2.1602 vs 1.9720）。
 * <p>修复＝fork 将裸 STDDEV/VARIANCE 的 kind 对齐总体。本锁钉住该别名映射，并守<b>显式带后缀</b>的四个算子
 * （{@code STDDEV_POP/STDDEV_SAMP/VAR_POP/VAR_SAMP}）kind 不受波及——确保只改无后缀默认、不破坏显式样本/总体语义。
 */
class MysqlStddevVarianceKindTest {

    @Test
    void bareStddevResolvesToPopulation() {
        assertEquals(SqlKind.STDDEV_POP, SqlStdOperatorTable.STDDEV.getKind(),
                "MySQL 裸 STDDEV()/STD() 默认=总体，应规约为 STDDEV_POP（÷N）");
    }

    @Test
    void bareVarianceResolvesToPopulation() {
        assertEquals(SqlKind.VAR_POP, SqlStdOperatorTable.VARIANCE.getKind(),
                "MySQL 裸 VARIANCE()/VAR() 默认=总体，应规约为 VAR_POP（÷N）");
    }

    @Test
    void explicitSuffixedOperatorsUnaffected() {
        // 守修复 blast：显式带后缀者语义各自固定，不得因裸别名改动而被牵连。
        assertEquals(SqlKind.STDDEV_POP, SqlStdOperatorTable.STDDEV_POP.getKind());
        assertEquals(SqlKind.STDDEV_SAMP, SqlStdOperatorTable.STDDEV_SAMP.getKind(),
                "显式 STDDEV_SAMP 必须仍为样本口径（÷(N-1)）");
        assertEquals(SqlKind.VAR_POP, SqlStdOperatorTable.VAR_POP.getKind());
        assertEquals(SqlKind.VAR_SAMP, SqlStdOperatorTable.VAR_SAMP.getKind(),
                "显式 VAR_SAMP 必须仍为样本口径（÷(N-1)）");
    }
}
