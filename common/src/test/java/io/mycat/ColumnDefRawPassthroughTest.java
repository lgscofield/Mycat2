package io.mycat;

import io.mycat.beans.mycat.MycatMySQLRowMetaData;
import io.mycat.beans.mycat.MycatRowMetaData;
import io.mycat.beans.mycat.ResultSetBuilder;
import io.mycat.beans.mycat.RowMetaDataFidelity;
import io.mycat.beans.mysql.packet.ColumnDefPacket;
import io.mycat.beans.mysql.packet.ColumnDefPacketImpl;
import io.mycat.protocol.mysql.type.MySQLFieldsType;
import io.mycat.protocol.mysql.type.MySQLPayloadWriter;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * I-DEBT-45：后端忠实轨（FaithfulTypeView 名对齐守卫通过的列）列头必须 raw 透传后端原始
 * column-def 包字节，杜绝 {@code MySQLFieldsType.fromJdbcType} 有损折叠
 * （实证：5.7 后端 TINY_BLOB+binary charset 的 VARBINARY(20) 被重建为 LONG_BLOB(251)/255）。
 * 异名列维持重建轨（I-DEBT-42 守卫同源）。
 */
class ColumnDefRawPassthroughTest {

    /** 5.7/MariaDB 后端 VARBINARY(20) 的 wire 形态：TINY_BLOB(249) + 二元字符集 + BLOB|BINARY flag。 */
    private static ColumnDefPacketImpl backendVarbinaryPacket(String name) {
        ColumnDefPacketImpl p = new ColumnDefPacketImpl();
        p.setColumnSchema("dumpschema".getBytes());
        p.setColumnTable("tbit".getBytes());
        p.setColumnOrgTable("tbit".getBytes());
        p.setColumnName(name.getBytes());
        p.setColumnOrgName(name.getBytes());
        p.setColumnCharsetSet(63);
        p.setColumnLength(20);
        p.setColumnType(MySQLFieldsType.FIELD_TYPE_TINY_BLOB);
        p.setColumnFlags(MySQLFieldsType.BLOB_FLAG | MySQLFieldsType.BINARY_FLAG);
        p.setColumnDecimals((byte) 0);
        return p;
    }

    private static MycatMySQLRowMetaData backendMeta(ColumnDefPacketImpl... packets) {
        return new MycatMySQLRowMetaData(Arrays.asList(packets));
    }

    private static MycatRowMetaData planMeta(String columnName, int jdbcType) {
        return ResultSetBuilder.create()
                .addColumnInfo(columnName, jdbcType)
                .build().getMetaData();
    }

    private static byte[] rawBytesOf(ColumnDefPacketImpl packet) {
        try (MySQLPayloadWriter writer = new MySQLPayloadWriter(128)) {
            packet.writePayload(writer);
            return writer.toByteArray();
        }
    }

    /** 解析 column-def payload：6 个 lenenc 串 → nextlength(1) → charset(2) → length(4) → type(1)。 */
    private static int[] parseCharsetLengthType(byte[] payload) {
        int pos = 0;
        for (int i = 0; i < 6; i++) {
            int len = payload[pos] & 0xff;
            if (len >= 0xfb) {
                throw new IllegalStateException("unexpected lenenc prefix in test payload");
            }
            pos += 1 + len;
        }
        pos += 1; // lenenc 0x0c
        int charset = (payload[pos] & 0xff) | ((payload[pos + 1] & 0xff) << 8);
        pos += 2;
        int length = (payload[pos] & 0xff) | ((payload[pos + 1] & 0xff) << 8)
                | ((payload[pos + 2] & 0xff) << 16) | ((payload[pos + 3] & 0xff) << 24);
        pos += 4;
        int type = payload[pos] & 0xff;
        return new int[]{charset, length, type};
    }

    private static List<byte[]> emit(MycatRowMetaData metaData) {
        List<byte[]> out = new ArrayList<>();
        for (byte[] b : MySQLPacketUtil.generateAllColumnDefPayload(metaData)) {
            out.add(b);
        }
        return out;
    }

    @Test
    void alignedColumnEmitsBackendRawPacket() {
        ColumnDefPacketImpl backendPacket = backendVarbinaryPacket("v");
        // 计划轨（有损）：同名列但 jdbc 型走重建会被折叠为 LONG_BLOB(251)/255。
        MycatRowMetaData effective = RowMetaDataFidelity.effective(
                backendMeta(backendPacket), planMeta("v", Types.LONGVARBINARY));

        List<byte[]> payloads = emit(effective);

        assertEquals(1, payloads.size());
        assertArrayEquals(rawBytesOf(backendPacket), payloads.get(0),
                "名对齐守卫通过列必须逐字节透传后端原始 column-def 包");
        int[] clt = parseCharsetLengthType(payloads.get(0));
        assertEquals(63, clt[0]);
        assertEquals(20, clt[1], "VARBINARY(20) 的 length 不得被重建为 255");
        assertEquals(MySQLFieldsType.FIELD_TYPE_TINY_BLOB, clt[2], "wire 型不得被 fromJdbcType 折叠为 LONG_BLOB");
    }

    @Test
    void misalignedColumnFallsBackToRebuild() {
        ColumnDefPacketImpl backendPacket = backendVarbinaryPacket("v");
        // 异名守卫（I-DEBT-42 同源）：后端列 v，计划列 w → 不透传，维持重建轨。
        MycatRowMetaData effective = RowMetaDataFidelity.effective(
                backendMeta(backendPacket), planMeta("w", Types.INTEGER));

        List<byte[]> payloads = emit(effective);

        assertEquals(1, payloads.size());
        int[] clt = parseCharsetLengthType(payloads.get(0));
        assertEquals(MySQLFieldsType.FIELD_TYPE_LONG, clt[2], "异名列按计划轨 jdbc INTEGER 重建");
        assertEquals("w".getBytes().length, (payloads.get(0)[0] & 0xff) > 0 ? payloadNameLen(payloads.get(0)) : -1);
    }

    private static int payloadNameLen(byte[] payload) {
        // 第 5 个 lenenc 串（columnName）长度：跳过前 4 个。
        int pos = 0;
        for (int i = 0; i < 4; i++) {
            pos += 1 + (payload[pos] & 0xff);
        }
        return payload[pos] & 0xff;
    }

    @Test
    void plainBackendMetaUnchanged() {
        // 零回归锁：裸 MycatMySQLRowMetaData 分支输出与原始包逐字节一致（改前改后同行为）。
        ColumnDefPacketImpl packet = backendVarbinaryPacket("v");
        List<byte[]> payloads = emit(backendMeta(packet));
        assertArrayEquals(rawBytesOf(packet), payloads.get(0));
    }

    @Test
    void emitsPlanColumnCountOnly() {
        // 尾部下推排序键（backend 列数 > plan 列数）：只发射 plan 输出列，多余尾列忽略。
        ColumnDefPacketImpl v = backendVarbinaryPacket("v");
        ColumnDefPacketImpl tail = backendVarbinaryPacket("ord_key");
        MycatRowMetaData effective = RowMetaDataFidelity.effective(
                backendMeta(v, tail), planMeta("v", Types.LONGVARBINARY));

        List<byte[]> payloads = emit(effective);

        assertEquals(1, payloads.size());
        assertArrayEquals(rawBytesOf(v), payloads.get(0));
    }

    @Test
    void heterogeneousProbeSuppressionDisablesRawPassthrough() {
        // 终审 Important-1：异构探测权威（probeMergedBackendMeta 合成 SQL 结果）
        // 列名天然对齐（union 输出列=计划列），但物理表名/库名是探测副作用不应透传给客户端。
        // effectiveWithSuppressedRaw 保留类型忠实，但 rawColumnDef 一律回 null → 发射缝走重建轨。
        ColumnDefPacketImpl probePacket = backendVarbinaryPacket("c");
        probePacket.setColumnTable("_probe_tmp_".getBytes());
        probePacket.setColumnSchema("_probe_ds_".getBytes());
        MycatRowMetaData suppressed = RowMetaDataFidelity.effectiveWithSuppressedRaw(
                backendMeta(probePacket), planMeta("c", Types.DECIMAL));

        // rawColumnDef 被抑制 → null
        assertNull(suppressed.rawColumnDef(0),
                "异构探测权威必须抑制 rawColumnDef（Review Focus#1：多源异构不得进透传分支）");

        // 列头仍取忠实类型（getColumnType 不受抑制影响：后端列名对齐仍取 VARBINARY）
        // 但发射走重建轨（因为 rawColumnDef=null + 整体非 MycatMySQLRowMetaData 实例）：
        // 重建轨经 MySQLFieldsType.fromJdbcType(VARBINARY) → LONG_BLOB(251)
        // 这证明合成 SQL 的物理表名/库名不会透传到客户端。
        List<byte[]> payloads = emit(suppressed);
        int[] clt = parseCharsetLengthType(payloads.get(0));
        assertEquals(MySQLFieldsType.FIELD_TYPE_LONG_BLOB, clt[2],
                "抑制透传后异构探测权威走重建轨（不原样透传探测包字节）");
        // 确认发射的 payload 不包含探测物理表名（重建轨的 table 字段为空串）
        String payloadStr = new String(payloads.get(0));
        assertEquals(false, payloadStr.contains("_probe_tmp_"),
                "物理表名不得出现在客户端列头");
        assertEquals(false, payloadStr.contains("_probe_ds_"),
                "物理库名不得出现在客户端列头");
    }
}
