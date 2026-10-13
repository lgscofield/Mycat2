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
}
