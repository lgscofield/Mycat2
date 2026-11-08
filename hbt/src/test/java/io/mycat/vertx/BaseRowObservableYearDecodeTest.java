/*
 * I-DEBT-87 离线锁：YEAR 语义值在整数列分派中的解码保命。
 *
 * 活体 RED 证据（修复前）：audit/2026-11-08-capability-audit —— `SELECT YEAR('2026-11-08')`
 * 经 8066 → ERROR 2013 会话强断；boot2 日志 L31350 全栈：
 *   BaseRowObservable.getObjects:362 throw UnsupportedOperationException("2026-01-01")
 *   ← VertxExecuter$4.onRow:911 ← NewMycatConnectionImpl.prepareQuery:156（JDBC 腿）
 * 根因（T1 已钉死）：JDBC 腿 `resultSet.getObject(i)`（NewMycatConnectionImpl:146/158）对后端
 * YEAR 线缆类型(13) 因 Connector/J yearIsDateType 缺省=true 返回 java.sql.Date；
 * mycat 元数据声明为整数族（BaseRowObservable:343-346 TINYINT/SMALLINT/INTEGER/BIGINT 分支），
 * Date 落 else(:362) throw。修法对齐同点 I-DEBT-41 byte[] 先例：整数分支接住 Date/LocalDate
 * 并按年份语义还原数值（YEAR 值 2026 → Date 2026-01-01 → getYear()=2026）。
 */
package io.mycat.vertx;

import io.mycat.beans.mycat.MycatRowMetaData;
import io.mycat.vertx.BaseRowObservable;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class BaseRowObservableYearDecodeTest {

    private static MycatRowMetaData singleIntColumn(String name) {
        return new MycatRowMetaData() {
            @Override public int getColumnCount() { return 1; }
            @Override public boolean isAutoIncrement(int column) { return false; }
            @Override public boolean isCaseSensitive(int column) { return false; }
            @Override public boolean isSigned(int column) { return true; }
            @Override public int getColumnDisplaySize(int column) { return 4; }
            @Override public String getColumnName(int column) { return name; }
            @Override public String getSchemaName(int column) { return ""; }
            @Override public int getPrecision(int column) { return 4; }
            @Override public int getScale(int column) { return 0; }
            @Override public String getTableName(int column) { return ""; }
            @Override public int getColumnType(int column) { return Types.INTEGER; }
            @Override public String getColumnLabel(int column) { return name; }
            @Override public ResultSetMetaData metaData() { return null; }
            @Override public boolean isNullable(int column) { return true; }
        };
    }

    @Test
    public void javaSqlDateInIntColumnDecodesToYear() {
        // Connector/J yearIsDateType=true 时 YEAR(13) 列 getObject 的实际形态
        Object[] got = BaseRowObservable.getObjects(
                new Object[]{Date.valueOf("2026-01-01")}, singleIntColumn("year"));
        assertEquals(2026L, ((Number) got[0]).longValue());
    }

    @Test
    public void localDateInIntColumnDecodesToYear() {
        // 原生腿若产出 LocalDate 形态（防御性接住），同样按年份语义还原
        Object[] got = BaseRowObservable.getObjects(
                new Object[]{LocalDate.of(2026, 1, 1)}, singleIntColumn("year"));
        assertEquals(2026L, ((Number) got[0]).longValue());
    }

    @Test
    public void plainNumberUnchanged() {
        Object[] got = BaseRowObservable.getObjects(new Object[]{2026}, singleIntColumn("year"));
        assertEquals(2026L, ((Number) got[0]).longValue());
    }

    @Test
    public void nullStaysNull() {
        Object[] got = BaseRowObservable.getObjects(new Object[]{null}, singleIntColumn("year"));
        assertNull(got[0]);
    }
}
