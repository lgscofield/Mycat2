package io.mycat.util;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlCreateTableStatement;
import io.mycat.beans.mycat.MycatRowMetaData;
import org.junit.Assert;
import org.junit.Test;

/**
 * I-DEBT-66 Fix-Meta-2（列元数据 DDL 供给腿）：{@link MycatRowMetaDataImpl} 由 CREATE TABLE 文本解析构造，
 * 旧实现 {@code getPrecision/getScale} 硬编码 0——分片表若走 DDL 腿（schema 配置里的 createTableSQL 文本，
 * 而非 JDBC 元数据腿 {@code convertfromDatabaseMetaData}），{@code SimpleColumnInfo} 的精度/标度即全丢，
 * 令 Fix-Meta（CalciteConvertors.getRelDataType 的 {@code precision>0} 守卫）不触发，DECIMAL 列塌回
 * (19,0) → deriveAvgAggType 少 4 档 → 跨片 AVG 归并腿出伪降 scale CAST → B1 落地后 C11 活库回归红
 * （mycat=3.6667 oracle=3.66666667，spec §2.6/§2.8 链条的最后缺口）。
 *
 * <p>本锁要求：DDL 解析腿与 JDBC 腿对齐——从列定义的 SQLDataType 参数读声明 precision/scale。
 */
public class MycatRowMetaDataImplPrecisionScaleTest {

    private static MycatRowMetaData metaOf(String ddl) {
        MySqlCreateTableStatement stmt =
                (MySqlCreateTableStatement) SQLUtils.parseSingleMysqlStatement(ddl);
        return new MycatRowMetaDataImpl(stmt);
    }

    @Test
    public void decimalColumnExposesDeclaredPrecisionAndScale() {
        MycatRowMetaData meta = metaOf(
                "create table t (id int primary key, d decimal(20, 4))");
        // 列序 1 = d：precision 20（超 fork max 19 由下游 getRelDataType clamp，此处透传声明值）、scale 4
        Assert.assertEquals(20, meta.getPrecision(1));
        Assert.assertEquals(4, meta.getScale(1));
    }

    @Test
    public void decimalWithOnlyPrecisionExposesZeroScale() {
        MycatRowMetaData meta = metaOf(
                "create table t (id int primary key, d decimal(10))");
        Assert.assertEquals(10, meta.getPrecision(1));
        Assert.assertEquals(0, meta.getScale(1));
    }

    /** 反向护栏：无类型参数的列（datetime）保持 0/0——不触发 Fix-Meta 守卫，行为与现状一致，防过度泛化。 */
    @Test
    public void columnWithoutTypeArgumentsStaysZero() {
        MycatRowMetaData meta = metaOf(
                "create table t (id int primary key, ts datetime)");
        Assert.assertEquals(0, meta.getPrecision(1));
        Assert.assertEquals(0, meta.getScale(1));
    }
}
