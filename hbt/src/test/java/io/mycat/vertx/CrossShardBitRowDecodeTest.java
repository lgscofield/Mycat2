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
 * <p><b>⚠ 未接入生产装配</b>：本锁只证“喂忠实/归一 meta 即 byte[] 透传”的组件级契约，<em>不代表跨片
 * 端到端已闭合</em>。跨片响应须行解码缝与列头缝（{@code PrepareExecutor} 发出的 {@code MySQLColumnDef}）
 * <b>同移</b>为忠实轨，否则行值 byte[] 撞数值列头 wire 会沉默损坏（见 {@code VertxExecuter.runQuery}
 * onColumnDef 注释 + ledger Task2/3 Ruling）。异步 header wiring 与权威 e2e {@code caseCrossShardBitColumnFidelity}
 * 均 NO_DATA 待 Docker 2 分片 infra，故本锁保持 dormant（不进 {@code runQuery} 热路径）。
 */
public class CrossShardBitRowDecodeTest {

    private static final int FIELD_TYPE_BIT = 16;

    private static MycatMySQLRowMetaData faithfulBitMeta() {
        ColumnDefPacketImpl c = new ColumnDefPacketImpl();
        c.setColumnName("c_bit8".getBytes());
        c.setColumnType(FIELD_TYPE_BIT);
        c.setColumnFlags(0);
        c.setColumnCharsetSet(63);
        List<ColumnDefPacket> list = new ArrayList<>();
        list.add(c);
        return new MycatMySQLRowMetaData(list);
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
}
