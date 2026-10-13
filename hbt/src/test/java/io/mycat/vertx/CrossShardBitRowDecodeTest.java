package io.mycat.vertx;

import io.mycat.beans.mycat.MycatMySQLRowMetaData;
import io.mycat.beans.mycat.MycatRowMetaData;
import io.mycat.beans.mycat.ResultSetBuilder;
import io.mycat.beans.mysql.packet.ColumnDefPacket;
import io.mycat.beans.mysql.packet.ColumnDefPacketImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.sql.JDBCType;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * I-DEBT-34 跨片列头保真 · Task 2 行解码缝逻辑锁（in-process）：
 * {@link VertxExecuter#resolveSplitRowMetaData} 在跨片 split 路径须以后端忠实轨（{@code bit(n>1)}→
 * {@code Types.BIT}）为准，使 {@link BaseRowObservable#getObjects(Object[], MycatRowMetaData)} 的
 * {@code case BIT:} byte[] 透传分支接管，而非落数值分支抛 {@code UnsupportedOperationException} 崩会话。
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
    public void resolveSplitPrefersFaithfulBitColumnType() {
        MycatRowMetaData resolved = VertxExecuter.resolveSplitRowMetaData(faithfulBitMeta(), numericPlanMeta());
        Assertions.assertEquals(Types.BIT, resolved.getColumnType(0),
                "跨片行解码缝须以忠实轨 Types.BIT 为准，不得被计划派生数值轨覆盖");
    }

    @Test
    public void numericPlanMetaDecodeOfByteArrayThrows() {
        Object[] row = { new byte[]{(byte) 0xB3} };
        // 钉当前 bug 面：有损数值 meta 下 byte[] 落数值分支 → 抛（崩会话根因）
        Assertions.assertThrows(UnsupportedOperationException.class,
                () -> BaseRowObservable.getObjects(row, numericPlanMeta()));
    }

    @Test
    public void resolvedFaithfulMetaDecodesByteArray() {
        Object[] row = { new byte[]{(byte) 0xB3} };
        Object[] out = BaseRowObservable.getObjects(row,
                VertxExecuter.resolveSplitRowMetaData(faithfulBitMeta(), numericPlanMeta()));
        Assertions.assertArrayEquals(new byte[]{(byte) 0xB3}, (byte[]) out[0],
                "BIT(8)=0xB3 应 byte[] 原样透传");
    }
}
