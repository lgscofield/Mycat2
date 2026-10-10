package io.mycat.resultset;

import io.mycat.protocol.mysql.type.MySQLFieldsType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 二进制协议结果集列编码符合性守卫（I-DEBT-33）。
 *
 * <p>MySQL 二进制结果集行里，每列的字节布局由列定义声明的 FIELD_TYPE 决定。客户端
 * （Connector/J BinaryRowFactory）严格按 FIELD_TYPE 切行：NEWDECIMAL 属于"长度编码字符串"
 * （lenenc：先 lenenc 前缀给出后续 ASCII 十进制串的字节数，再跟串本身），不是定长裸整数。
 *
 * <p>本测试钉死：凡 {@link MySQLFieldsType#fromJdbcType} 映射到 NEWDECIMAL 的 jdbc 类型
 * （NUMERIC / DECIMAL），{@link BinaryResultSetResponse#getBytes} 必须产出 lenenc 十进制串，
 * 且两者对同一值字节完全一致。历史缺陷：NUMERIC 分支误用 convertToInt16（2 字节裸整数），
 * 与 DECIMAL（正确走 lenenc）在同一 FIELD_TYPE 下布局分叉，导致二进制行解析错位、连接被服务端切断。
 */
class BinaryResultSetResponseDecimalTest {

    /** '1' 的 ASCII = 0x31；lenenc 短串前缀即字节数本身 = 0x01。 */
    private static final byte[] LENENC_ONE = new byte[]{0x01, 0x31};

    @Test
    void numericAndDecimalBothMapToNewDecimalFieldType() {
        // 前置：确认这两个 jdbc 类型在列定义里都声明为 NEWDECIMAL（同一 FIELD_TYPE）
        assertEquals(MySQLFieldsType.FIELD_TYPE_NEW_DECIMAL, MySQLFieldsType.fromJdbcType(Types.NUMERIC));
        assertEquals(MySQLFieldsType.FIELD_TYPE_NEW_DECIMAL, MySQLFieldsType.fromJdbcType(Types.DECIMAL));
    }

    @Test
    void decimalColumnEncodesAsLengthEncodedDecimalString() {
        // 正确基线：DECIMAL 已经是 lenenc（守绿）
        assertArrayEquals(LENENC_ONE, BinaryResultSetResponse.getBytes(Types.DECIMAL, BigDecimal.valueOf(1)));
    }

    @Test
    void numericColumnEncodesAsLengthEncodedDecimalString() {
        // 缺陷靶心：NUMERIC 曾误发 int16 [01 00]，应等同 DECIMAL 的 lenenc [01 31]
        assertArrayEquals(LENENC_ONE, BinaryResultSetResponse.getBytes(Types.NUMERIC, BigDecimal.valueOf(1)));
    }
}
