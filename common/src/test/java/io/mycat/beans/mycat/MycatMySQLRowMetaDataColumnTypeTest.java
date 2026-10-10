package io.mycat.beans.mycat;

import io.mycat.beans.mysql.packet.ColumnDefPacket;
import io.mycat.beans.mysql.packet.ColumnDefPacketImpl;
import org.junit.Assert;
import org.junit.Test;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * I-DEBT-33 Bug B 回归锁：{@link MycatMySQLRowMetaData#getColumnType(int)} 必须把后端
 * 列定义的 MySQL FIELD_TYPE 解析成与"二进制协议声明宽度"一致的 java.sql.Types。
 *
 * <p>历史缺陷：getColumnType 调 {@code DataType.parseDataType(type, flags, charset)}，
 * 而 vertx 真实签名是 {@code parseDataType(short type, int charsetIndex, int flags)}——
 * flags 与 charset 传参顺序颠倒，导致数字列的 charset(63) 被当作 flags，其 0x20 位被误读为
 * "无符号"，vertx 返回 U_INT* 变体并把 jdbc 型加宽一档（BIGINT→NUMERIC、INT→BIGINT、
 * SMALLINT→INTEGER、TINYINT→SMALLINT）。二进制结果集的值编码器据此发出错误字节宽度，与透传的
 * 列定义（LONGLONG 8B）不符，客户端切行时 arraycopy 越界（case5/case14 真服务端根）。
 *
 * <p>MySQL FIELD_TYPE 常量（协议字节值）：TINY=1 SHORT=2 LONG=3 LONGLONG=8 NEWDECIMAL=246。
 */
public class MycatMySQLRowMetaDataColumnTypeTest {

    private static final int FIELD_TYPE_TINY = 1;
    private static final int FIELD_TYPE_SHORT = 2;
    private static final int FIELD_TYPE_LONG = 3;
    private static final int FIELD_TYPE_LONGLONG = 8;
    private static final int FIELD_TYPE_NEWDECIMAL = 246;
    private static final int CHARSET_BINARY = 63;

    private static MycatMySQLRowMetaData oneColumn(int fieldType, int flags) {
        ColumnDefPacketImpl c = new ColumnDefPacketImpl();
        c.setColumnName("x".getBytes());
        c.setColumnType(fieldType);
        c.setColumnFlags(flags);
        c.setColumnCharsetSet(CHARSET_BINARY);
        List<ColumnDefPacket> list = new ArrayList<>();
        list.add(c);
        return new MycatMySQLRowMetaData(list);
    }

    @Test
    public void signedLonglongMapsToBigint() {
        // case5/case14 靶心：signed BIGINT 列必须是 BIGINT（曾被误判成 NUMERIC）
        Assert.assertEquals(Types.BIGINT, oneColumn(FIELD_TYPE_LONGLONG, 0).getColumnType(0));
    }

    @Test
    public void signedLongMapsToInteger() {
        Assert.assertEquals(Types.INTEGER, oneColumn(FIELD_TYPE_LONG, 0).getColumnType(0));
    }

    @Test
    public void signedShortMapsToSmallint() {
        Assert.assertEquals(Types.SMALLINT, oneColumn(FIELD_TYPE_SHORT, 0).getColumnType(0));
    }

    @Test
    public void signedTinyMapsToTinyint() {
        Assert.assertEquals(Types.TINYINT, oneColumn(FIELD_TYPE_TINY, 0).getColumnType(0));
    }

    @Test
    public void newDecimalMapsToDecimalOrNumeric() {
        // NEWDECIMAL 是变长 lenenc，DECIMAL/NUMERIC 均落 convertString 分支，宽度一致、可接受
        int jdbc = oneColumn(FIELD_TYPE_NEWDECIMAL, 0).getColumnType(0);
        Assert.assertTrue("NEWDECIMAL 应解析为 DECIMAL/NUMERIC 之一，实得 " + jdbc,
                jdbc == Types.DECIMAL || jdbc == Types.NUMERIC);
    }
}
