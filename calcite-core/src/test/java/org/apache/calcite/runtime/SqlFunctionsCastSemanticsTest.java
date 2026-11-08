package org.apache.calcite.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

// F3/G3 离线锁: MySQL 串转数语义(非法=0, 绝不抛)。
// 活体 RED(修复前): SELECT CAST('abc' AS SIGNED) 经 8066 → NumberFormatException 杀会话;
// 真库=0。mycat 修改点: SqlFunctions.toInt(String)/toLong(String)(MYCAT CHANGE 标记)。
public class SqlFunctionsCastSemanticsTest {
  @Test public void invalidStringCastsToZero() {
    assertEquals(0, SqlFunctions.toInt("abc"));
    assertEquals(0L, SqlFunctions.toLong("abc"));
  }
  @Test public void validStringsUnchanged() {
    assertEquals(42, SqlFunctions.toInt("42"));
    assertEquals(42L, SqlFunctions.toLong("42"));
    assertEquals(0, SqlFunctions.toInt(""));
  }
}
