package io.mycat;

import io.mycat.beans.mycat.MycatMySQLRowMetaData;
import io.mycat.beans.mycat.MycatRowMetaData;
import io.mycat.beans.mycat.ResultSetBuilder;
import io.mycat.beans.mycat.RowMetaDataFidelity;
import io.mycat.beans.mysql.packet.ColumnDefPacket;
import io.mycat.beans.mysql.packet.ColumnDefPacketImpl;
import org.junit.Assert;
import org.junit.Test;

import java.sql.JDBCType;
import java.util.ArrayList;
import java.util.List;

/**
 * I-DEBT-34 跨片列头保真 · Task 3 列头 payload 语义锁（in-process）。
 *
 * <p>钉住“响应列头 {@code columnDef} 的 field-type 字节由喂给 {@link MySQLPacketUtil#generateColumnDefPayload}
 * 的 meta 决定”这一契约：忠实轨直写 {@code MYSQL_TYPE_BIT=16}；有损计划轨（{@code bit(n)}→BIGINT）经
 * else 分支重建降为 {@code FIELD_TYPE_LONGLONG=8}（当前 bug 面）；而 {@link RowMetaDataFidelity#effective}
 * 归一视图（{@code getColumnType} 取后端物理 {@code Types.BIT}）经 else 分支 {@code fromJdbcType(BIT)→16}
 * 重建，列头复现 BIT。此为跨片列头装配缝（{@code PrepareExecutor}/{@code ObservablePlanImplementorImpl}）
 * 上抛首分片忠实 meta 后所依赖的字节级契约——把归一视图送入列头即得 {@code MYSQL_TYPE_BIT}。
 */
public class MySQLPacketUtilBitHeaderTest {

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

    /** 取第 0 列的 columnDef payload。 */
    private static byte[] firstColumnDefPayload(MycatRowMetaData meta) {
        return MySQLPacketUtil.generateColumnDefPayload(meta, 0);
    }

    /**
     * 解析 columnDef payload 的 field-type 字节。布局：6 个 lenenc 串（null/空→单字节 0x00）
     * + lenenc int 0x0c（<251→1 字节）+ charset(2) + columnLength(4) + type(1)。
     */
    private static int fieldTypeIdOf(byte[] payload) {
        int pos = 0;
        for (int i = 0; i < 6; i++) {
            int len = payload[pos] & 0xFF; // 本组 fixture 长度均 < 251，单字节 lenenc
            pos += 1 + len;
        }
        pos += 1; // lenenc int 0x0c
        pos += 2; // charset
        pos += 4; // columnLength
        return payload[pos] & 0xFF;
    }

    @Test
    public void faithfulTrackHeaderEmitsMysqlTypeBit() {
        int type = fieldTypeIdOf(firstColumnDefPayload(faithfulBitMeta()));
        Assert.assertEquals("忠实轨直写后端 columnDefPacket，列头须为 MYSQL_TYPE_BIT", FIELD_TYPE_BIT, type);
    }

    @Test
    public void lossyPlanHeaderDowngradesFromBit() {
        int type = fieldTypeIdOf(firstColumnDefPayload(numericPlanMeta()));
        Assert.assertNotEquals("有损计划轨(BIGINT)列头不得是 BIT——钉当前跨片降型 bug 面", FIELD_TYPE_BIT, type);
    }

    @Test
    public void normalizedFidelityViewHeaderEmitsMysqlTypeBit() {
        MycatRowMetaData normalized = RowMetaDataFidelity.effective(faithfulBitMeta(), numericPlanMeta());
        Assert.assertFalse("归一视图非忠实轨实例，须走 else 分支重建",
                normalized instanceof MycatMySQLRowMetaData);
        int type = fieldTypeIdOf(firstColumnDefPayload(normalized));
        Assert.assertEquals("归一视图 getColumnType=Types.BIT → fromJdbcType→16，列头复现 BIT", FIELD_TYPE_BIT, type);
    }
}
