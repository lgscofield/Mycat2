/**
 * Copyright (C) <2026> <Mycat2>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.mycatmysql;

import io.mycat.vertx.ReadView;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * I-DEBT-123：COM_STMT_EXECUTE 参数 NULL 位图 +2 偏移锁。
 *
 * <p>背景：MySQL 二进制协议的 NULL 位图统一 +2 偏移——位图前两位是 status/param_count 预留位，
 * 参数 i 的 NULL 位落在 {@code (i+2)/8 字节的 (i+2)&7 位}。本仓编码端
 * {@code MySQLPacketUtil.generateBinaryRowHeader}（common:232,255）按 {@code (columnIndex + 2)}
 * 写位；而 {@code MycatVertxMySQLHandler} COM_STMT_EXECUTE 解码端按参数序号裸取
 * {@code (nullMap[i / 8] & (1 << (i & 7)))}——错位 2 位：客户端发来的 NULL 参数被解码成非 NULL
 * （拿残留位图数据当参数值），非 NULL 参数可能被判 NULL。位图读取长度也须同步为
 * {@code (numParams + 9) / 8}（原 {@code (numParams+7)/8} 少 1 字节，把后续字段错位吞入位图）。
 *
 * <p>锁策略：测试内按协议/编码端同口径（+2 偏移）构造含 NULL 参数的完整 EXECUTE 载荷，
 * 经 {@code ReadView} + 被测解码 seam（{@code readStmtExecuteNullBitmap}/{@code isStmtExecuteParamNull}）
 * 解码，断言每个参数 NULL 形态与编码意图一致、且解码后流恰好耗尽（无错位）。
 * RED 先行：现解码错位 → NULL 形态错/流残留。
 */
public class StmtExecuteNullBitmapTest {

    private static final int MYSQL_TYPE_VAR_STRING = 253;

    /** 按协议 +2 偏移构造 NULL 位图（与 MySQLPacketUtil.generateBinaryRowHeader 编码端同口径）。 */
    private static byte[] encodeNullBitmap(int numParams, Set<Integer> nullIndexes) {
        byte[] nullMap = new byte[(numParams + 9) / 8];
        for (int i = 0; i < numParams; i++) {
            if (nullIndexes.contains(i)) {
                nullMap[(i + 2) / 8] |= (byte) (1 << ((i + 2) & 7));
            }
        }
        return nullMap;
    }

    /** 构造 COM_STMT_EXECUTE 载荷：header + NULL 位图 + new_params_bound_flag + 参数类型 + 参数值。 */
    private static Buffer encodeExecute(int numParams, Set<Integer> nullIndexes) {
        Buffer buffer = Buffer.buffer();
        // 注意：Vert.x Buffer 默认 BIG-endian，MySQL 协议整数是小端——数值字段一律用 *LE 变体
        buffer.appendIntLE(1);                      // statement_id (4 bytes LE)
        buffer.appendByte((byte) 0);                // flags
        buffer.appendIntLE(1);                      // iteration-count (4 bytes LE)
        buffer.appendBytes(encodeNullBitmap(numParams, nullIndexes));
        buffer.appendByte((byte) 1);                // new-params-bound-flag = 1
        for (int i = 0; i < numParams; i++) {
            buffer.appendUnsignedShortLE(MYSQL_TYPE_VAR_STRING);
        }
        for (int i = 0; i < numParams; i++) {
            if (!nullIndexes.contains(i)) {
                String v = "v" + i;
                buffer.appendUnsignedByte((byte) v.length());
                buffer.appendString(v);
            }
        }
        return buffer;
    }

    /** 用被测 seam 完整解码 EXECUTE 载荷，返回每个参数的 NULL 形态（true=NULL）。 */
    private static boolean[] decodeNullForms(Buffer payload, int numParams) {
        ReadView readView = new ReadView(payload);
        readView.readFixInt(4);   // statement_id
        readView.readByte();      // flags
        readView.readFixInt(4);   // iteration
        byte[] nullMap = MycatVertxMySQLHandler.readStmtExecuteNullBitmap(readView, numParams);
        Assertions.assertEquals((numParams + 9) / 8, nullMap.length,
                "NULL 位图读取长度必须为 (numParams+2+7)/8（协议 +2 偏移），错位将吞掉后续字段");

        boolean newParamsBoundFlag = !readView.readFinished() && readView.readByte() == 1;
        Assertions.assertTrue(newParamsBoundFlag,
                "位图读取长度错位时 new-params-bound-flag 会被位图残留字节顶掉（流错位证据）");
        for (int i = 0; i < numParams; i++) {
            Assertions.assertEquals(MYSQL_TYPE_VAR_STRING, (int) readView.readFixInt(2),
                    "参数类型流必须逐位对齐（i=" + i + "）");
        }
        boolean[] nullForms = new boolean[numParams];
        for (int i = 0; i < numParams; i++) {
            nullForms[i] = MycatVertxMySQLHandler.isStmtExecuteParamNull(nullMap, i);
            if (!nullForms[i]) {
                // 非 NULL 参数跟一个 lenenc 字符串值；NULL 形态解码正确时流才对得齐
                Assertions.assertEquals("v" + i, readView.readLenencString(),
                        "非 NULL 参数的值流必须逐位对齐（i=" + i + "）");
            }
        }
        return nullForms;
    }

    private static void assertNullForms(int numParams, Set<Integer> nullIndexes, boolean[] decoded) {
        for (int i = 0; i < numParams; i++) {
            Assertions.assertEquals(nullIndexes.contains(i), decoded[i],
                    "参数 " + i + " 的 NULL 形态必须与编码意图一致（协议 +2 偏移）");
        }
    }

    @Test
    void nullAtFirstAndLastParam_decodeCorrect() {
        int numParams = 10;
        Set<Integer> nulls = new HashSet<>();
        nulls.add(0);   // 位 2（字节 0）
        nulls.add(9);   // 位 11（字节 1）——现解码按 i/8=i&7 取位必错
        boolean[] decoded = decodeNullForms(encodeExecute(numParams, nulls), numParams);
        assertNullForms(numParams, nulls, decoded);
    }

    @Test
    void bitmapCrossesByteBoundary_decodeCorrect() {
        int numParams = 7;  // 位图需 (7+9)/8=2 字节；现读取长度 (7+7)/8=1 字节 → 流错位
        Set<Integer> nulls = new HashSet<>();
        nulls.add(6);       // 位 8（字节 1）——落在现码读不到的第 2 字节
        boolean[] decoded = decodeNullForms(encodeExecute(numParams, nulls), numParams);
        assertNullForms(numParams, nulls, decoded);
    }

    @Test
    void allNullParams_decodeCorrect() {
        int numParams = 3;
        Set<Integer> nulls = new HashSet<>();
        for (int i = 0; i < numParams; i++) {
            nulls.add(i);
        }
        boolean[] decoded = decodeNullForms(encodeExecute(numParams, nulls), numParams);
        assertNullForms(numParams, nulls, decoded);
    }

    @Test
    void noNullParams_decodeCorrect() {
        int numParams = 4;
        boolean[] decoded = decodeNullForms(encodeExecute(numParams, new HashSet<>()), numParams);
        assertNullForms(numParams, new HashSet<>(), decoded);
    }
}
