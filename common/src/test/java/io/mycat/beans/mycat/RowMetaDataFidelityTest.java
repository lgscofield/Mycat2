package io.mycat.beans.mycat;

import io.mycat.beans.mysql.packet.ColumnDefPacket;
import io.mycat.beans.mysql.packet.ColumnDefPacketImpl;
import org.junit.Assert;
import org.junit.Test;

import java.sql.JDBCType;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * I-DEBT-34 跨片列头保真 · Task 1 逻辑锁：{@link RowMetaDataFidelity#effective} 在跨片归并/本地
 * 计划路径上，须以后端忠实轨（{@link MycatMySQLRowMetaData}，{@code columnDefPackets}）的物理 MySQL
 * 类型为列头/行值真相源，覆盖有损轨（计划派生 meta，bit(n) 归数值）。列名/label 仍以计划逻辑视图为准。
 *
 * <p>对照 {@link MycatMySQLRowMetaDataColumnTypeTest} 的 fixture 范式：MySQL FIELD_TYPE_BIT=16。
 */
public class RowMetaDataFidelityTest {

    private static final int FIELD_TYPE_BIT = 16;
    private static final int CHARSET_BINARY = 63;

    /** 忠实轨单列 BIT meta（模拟后端回包 columnDefPacket，FIELD_TYPE_BIT）。 */
    private static MycatMySQLRowMetaData faithfulBitMeta() {
        ColumnDefPacketImpl c = new ColumnDefPacketImpl();
        c.setColumnName("c_bit8".getBytes());
        c.setColumnType(FIELD_TYPE_BIT);
        c.setColumnFlags(0);
        c.setColumnCharsetSet(CHARSET_BINARY);
        List<ColumnDefPacket> list = new ArrayList<>();
        list.add(c);
        return new MycatMySQLRowMetaData(list);
    }

    /** 有损计划轨 meta（bit 列归数值 BIGINT），列名走逻辑视图。 */
    private static MycatRowMetaData numericPlanMeta() {
        return ResultSetBuilder.create()
                .addColumnInfo("c_bit8", JDBCType.BIGINT)
                .build().getMetaData();
    }

    /** 两列计划 meta，用于列数不等回退路径。 */
    private static MycatRowMetaData twoColumnPlanMeta() {
        return ResultSetBuilder.create()
                .addColumnInfo("c_bit8", JDBCType.BIGINT)
                .addColumnInfo("id", JDBCType.BIGINT)
                .build().getMetaData();
    }

    /**
     * I-DEBT-42：两列忠实轨后端 meta（模拟 join 的输入叶子扫描回包），两列都是 FIELD_TYPE_BIT，
     * 列名 {@code c_bit8}/{@code traveldate}——刻意与计划轨的输出列名/顺序不对齐。
     */
    private static MycatMySQLRowMetaData twoColFaithfulBackend() {
        List<ColumnDefPacket> list = new ArrayList<>();
        list.add(bitCol("c_bit8"));
        list.add(bitCol("traveldate"));
        return new MycatMySQLRowMetaData(list);
    }

    private static ColumnDefPacketImpl bitCol(String name) {
        ColumnDefPacketImpl c = new ColumnDefPacketImpl();
        c.setColumnName(name.getBytes());
        c.setColumnType(FIELD_TYPE_BIT);
        c.setColumnFlags(0);
        c.setColumnCharsetSet(CHARSET_BINARY);
        return c;
    }

    /**
     * I-DEBT-42：join 输出计划轨，col0 名 {@code c_bit8} 与后端对齐，col1 名 {@code companyname} 与后端
     * {@code traveldate} 错位；col1 真实类型 VARCHAR（承载 "Intel" 之类字符串），后端却声明 BIT/DATE。
     */
    private static MycatRowMetaData joinMisalignedPlanMeta() {
        return ResultSetBuilder.create()
                .addColumnInfo("c_bit8", JDBCType.BIGINT)
                .addColumnInfo("companyname", JDBCType.VARCHAR)
                .build().getMetaData();
    }

    @Test
    public void backendFaithfulBitResolvesToTypesBit() {
        // 前提锁：忠实轨 FIELD_TYPE_BIT(16) 必须解析为 Types.BIT（parseDataType 真相源）
        Assert.assertEquals("忠实轨 BIT 列须解析为 Types.BIT",
                Types.BIT, faithfulBitMeta().getColumnType(0));
    }

    @Test
    public void effectivePrefersFaithfulBackendColumnType() {
        MycatRowMetaData backend = faithfulBitMeta();
        MycatRowMetaData plan = numericPlanMeta();
        MycatRowMetaData eff = RowMetaDataFidelity.effective(backend, plan);
        Assert.assertEquals(Types.BIT, eff.getColumnType(0));
        Assert.assertEquals(plan.getColumnLabel(0), eff.getColumnLabel(0));
    }

    @Test
    public void effectiveFallsBackToPlanWhenColumnCountDiffers() {
        MycatRowMetaData backend = faithfulBitMeta();   // 1 列
        MycatRowMetaData plan = twoColumnPlanMeta();    // 2 列
        Assert.assertSame(plan, RowMetaDataFidelity.effective(backend, plan));
    }

    @Test
    public void effectiveReturnsPlanWhenBackendNotFaithfulTrack() {
        // 后端非 MycatMySQLRowMetaData（如纯本地计算列无后端包）→ 返回计划 meta（C 兜底）
        MycatRowMetaData plan = numericPlanMeta();
        MycatRowMetaData nonFaithful = twoColumnPlanMeta();
        Assert.assertSame(plan, RowMetaDataFidelity.effective(nonFaithful, plan));
    }

    @Test
    public void effectiveReturnsBackendWhenPlanNull() {
        // 公共 API 护栏：planMeta 为 null（无计划视图）时不得 NPE，应回退后端忠实轨
        MycatMySQLRowMetaData backend = faithfulBitMeta();
        Assert.assertSame(backend, RowMetaDataFidelity.effective(backend, null));
    }

    /**
     * I-DEBT-42 红锁：join 等“输出非后端扫描直通”的计划，后端轨是输入叶子形状，与计划输出
     * 不逐位对齐。忠实覆写必须以“列名逐位对齐”为前提：同名列取后端保真类型，异名列回计划轨，
     * 否则会把后端 BIT/DATE 沉默套到完全不同的列上（真实现场：traveldate=DATE 套到 companyname="Intel"）。
     */
    @Test
    public void effectiveSuppressesOverrideWhenColumnNameMisaligned() {
        MycatRowMetaData backend = twoColFaithfulBackend();   // [c_bit8=BIT, traveldate=BIT]
        MycatRowMetaData plan = joinMisalignedPlanMeta();      // [c_bit8=BIGINT, companyname=VARCHAR]
        MycatRowMetaData eff = RowMetaDataFidelity.effective(backend, plan);
        Assert.assertEquals("同名列（col0 c_bit8）须保真取后端 Types.BIT",
                Types.BIT, eff.getColumnType(0));
        Assert.assertEquals("异名列（col1 traveldate≠companyname）须回退计划轨 Types.VARCHAR，不被后端覆盖",
                Types.VARCHAR, eff.getColumnType(1));
    }
}
