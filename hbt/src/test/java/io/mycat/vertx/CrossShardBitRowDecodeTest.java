package io.mycat.vertx;

import io.mycat.beans.mycat.MycatMySQLRowMetaData;
import io.mycat.beans.mycat.MycatRowMetaData;
import io.mycat.beans.mycat.ResultSetBuilder;
import io.mycat.beans.mycat.RowMetaDataFidelity;
import io.mycat.beans.mysql.packet.ColumnDefPacket;
import io.mycat.beans.mysql.packet.ColumnDefPacketImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.sql.JDBCType;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * I-DEBT-34 跨片列头保真 · 行解码契约逻辑锁（in-process，dormant）。
 *
 * <p>钉住跨片 split 行解码正确闭合所需的<b>契约</b>：{@link RowMetaDataFidelity#effective} 归一视图
 * 以忠实轨 {@code bit(n>1)}→{@code Types.BIT} 为准时，{@link BaseRowObservable#getObjects(Object[], MycatRowMetaData)}
 * 走 {@code case BIT:} byte[] 透传分支；而有损计划轨（BIGINT）下同 row 落数值分支抛
 * {@code UnsupportedOperationException}（崩溃根因）。
 *
 * <p><b>解码原语契约锁</b>（in-process）：本锁只证“喂忠实/归一 meta 即 byte[] 透传”的组件级契约。
 * 生产行解码缝已在 Task 2 接入 {@code VertxExecuter.runQuery.onColumnDef}——与列头缝（{@code PrepareExecutor}
 * 经 per-query carrier 用 {@code effective} 惰性重建）同移为忠实轨（INV-④）。跨片端到端的权威 RED→GREEN
 * 见证为活 e2e {@code caseCrossShardBitColumnFidelity}（infra 已起，两分片真库）；本锁作解码语义的回归守卫。
 */
public class CrossShardBitRowDecodeTest {

    private static final int FIELD_TYPE_BIT = 16;

    private static MycatMySQLRowMetaData faithfulBitMeta() {
        return new MycatMySQLRowMetaData(List.of(colDef("c_bit8", FIELD_TYPE_BIT)));
    }

    private static ColumnDefPacketImpl colDef(String name, int mysqlType) {
        return colDef(name, mysqlType, 256);
    }

    private static ColumnDefPacketImpl colDef(String name, int mysqlType, int columnLength) {
        ColumnDefPacketImpl c = new ColumnDefPacketImpl();
        c.setColumnName(name.getBytes());
        c.setColumnType(mysqlType);
        c.setColumnFlags(0);
        c.setColumnCharsetSet(63);
        c.setColumnLength(columnLength);
        return c;
    }

    private static MycatRowMetaData numericPlanMeta() {
        return ResultSetBuilder.create()
                .addColumnInfo("c_bit8", JDBCType.BIGINT)
                .build().getMetaData();
    }

    @Test
    public void effectiveResolvesToFaithfulBitColumnType() {
        MycatRowMetaData resolved = RowMetaDataFidelity.effective(faithfulBitMeta(), numericPlanMeta());
        Assertions.assertEquals(Types.BIT, resolved.getColumnType(0),
                "归一视图须以忠实轨 Types.BIT 为准，不被计划派生数值轨覆盖");
    }

    @Test
    public void effectiveAlignsWhenBackendHasTrailingSortKeyColumn() {
        // 跨片 order by：后端把排序键 id 追加下推 → 忠实轨 2 列(c_bit8 BIT, id BIGINT)，
        // 计划列头 project 掉 id → 1 列。旧守卫要求列数相等(2!=1)误回退有损轨(BIGINT)，
        // 与忠实解码(byte[])错配 → 客户端 NumberFormatException 沉默损坏。前导输出列须按位对齐取忠实 BIT。
        MycatMySQLRowMetaData backend2 =
                new MycatMySQLRowMetaData(List.of(colDef("c_bit8", FIELD_TYPE_BIT), colDef("id", 8)));
        MycatRowMetaData plan1 = ResultSetBuilder.create()
                .addColumnInfo("c_bit8", JDBCType.BIGINT).build().getMetaData();
        MycatRowMetaData resolved = RowMetaDataFidelity.effective(backend2, plan1);
        Assertions.assertEquals(Types.BIT, resolved.getColumnType(0),
                "后端尾部追加排序键时，前导输出列仍须按位对齐取忠实 BIT");
        Assertions.assertEquals(1, resolved.getColumnCount(), "列头列数以计划逻辑视图为准");
    }

    @Test
    public void lossyPlanMetaDecodeOfByteArrayThrows() {
        Object[] row = { new byte[]{(byte) 0xB3} };
        // 钉当前 bug 面：有损数值 meta 下 byte[] 落数值分支 → 抛（崩会话根因）
        Assertions.assertThrows(UnsupportedOperationException.class,
                () -> BaseRowObservable.getObjects(row, numericPlanMeta()));
    }

    @Test
    public void faithfulMetaDecodeOfByteArrayPassesThrough() {
        Object[] row = { new byte[]{(byte) 0xB3} };
        Object[] out = BaseRowObservable.getObjects(row, faithfulBitMeta());
        Assertions.assertArrayEquals(new byte[]{(byte) 0xB3}, (byte[]) out[0],
                "BIT(8)=0xB3 应 byte[] 原样透传");
    }

    @Test
    public void effectiveViewDecodeOfByteArrayPassesThrough() {
        Object[] row = { new byte[]{(byte) 0xB3} };
        Object[] out = BaseRowObservable.getObjects(row,
                RowMetaDataFidelity.effective(faithfulBitMeta(), numericPlanMeta()));
        Assertions.assertArrayEquals(new byte[]{(byte) 0xB3}, (byte[]) out[0],
                "归一视图喂行解码即走 case BIT byte[] 透传（跨片闭合所需契约）");
    }

    @Test
    public void faithfulViewCarriesBackendBitColumnLength() {
        // 跨片列头重建需真实位长：归一视图应将列显示长度取自后端忠实轨（bit(8)=8），不被计划有损轨覆盖。
        MycatMySQLRowMetaData backend =
                new MycatMySQLRowMetaData(List.of(colDef("c_bit8", FIELD_TYPE_BIT, 8), colDef("id", 8, 8)));
        MycatRowMetaData plan1 = ResultSetBuilder.create()
                .addColumnInfo("c_bit8", JDBCType.BIGINT).build().getMetaData();
        MycatRowMetaData resolved = RowMetaDataFidelity.effective(backend, plan1);
        Assertions.assertEquals(8, resolved.getColumnDisplaySize(0),
                "归一视图须以后端位长为准供列头重建");
    }

    @Test
    public void columnDefReconstructionUsesRealBitLengthNotOne() {
        // 跨片列头非 MycatMySQLRowMetaData → 走 ColumnDefPacketImpl 重建分支；旧代码将任何 BIT 硬编
        // columnLength=1 → Connector/J 读成 Boolean（与单片 raw 后端包 bit(8)→byte[] 不一致）。须用真实位长 8。
        MycatMySQLRowMetaData backend =
                new MycatMySQLRowMetaData(List.of(colDef("c_bit8", FIELD_TYPE_BIT, 8), colDef("id", 8, 8)));
        MycatRowMetaData plan1 = ResultSetBuilder.create()
                .addColumnInfo("c_bit8", JDBCType.BIGINT).build().getMetaData();
        MycatRowMetaData view = RowMetaDataFidelity.effective(backend, plan1);
        ColumnDefPacketImpl cd = new ColumnDefPacketImpl(view, 0);
        Assertions.assertEquals(8, cd.getColumnLength(),
                "BIT 列定义重建须取真实位长 8，不能硬编为 1");
    }
}
