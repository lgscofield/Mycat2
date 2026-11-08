// PROBE: P-B-05 标量函数 mycat vs 3306 直连逐值对拍(12 函数)
// 验证 I-DEBT-32-FAMILY 本地 codegen 声明(MD5/SHA1/SHA2/DAYOFWEEK/QUARTER/WEEK/DATEDIFF/LAST_DAY 等)
// PASS = 全部函数×全部测试值 mycat 结果与真库一致
import java.sql.*;
import java.util.*;

public class ProbeB_05 {
  public static void main(String[] args) throws Exception {
    String MYCAT = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    String BACKEND = "jdbc:mysql://127.0.0.1:3306/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    String[] exprs = {
      "MD5('mycat审计2026')", "SHA1('mycat审计2026')", "SHA2('mycat审计2026',256)",
      "DAYOFWEEK('2026-11-08')", "YEAR('2026-11-08')", "QUARTER('2026-11-08')",
      "WEEK('2026-11-08')", "DATEDIFF('2026-12-31','2026-11-08')", "LAST_DAY('2026-02-15')",
      "IFNULL(NULL,'x')", "7 DIV 2", "MOD(7,3)", "CONCAT('a','b',1)", "ABS(-42)",
      "UPPER('abc')", "LENGTH('中文')", "DATE_FORMAT('2026-11-08','%Y/%m/%d')",
      "STR_TO_DATE('08,11,2026','%d,%m,%Y')", "CAST('123' AS SIGNED)+1", "COALESCE(NULL,2,3)"
    };
    List<String> errs = new ArrayList<>();
    try (Connection b = DriverManager.getConnection(BACKEND, "audit", "Audit#2026");
         Statement bs = b.createStatement()) {
      for (String e : exprs) {
        String mine, want;
        // 每表达式独立 mycat 会话: 已知 YEAR() 会杀死会话(见审计发现), 隔离以测其余
        try (Connection m = DriverManager.getConnection(MYCAT, "root", "123456");
             Statement ms = m.createStatement()) {
          try { mine = scalar(ms, "SELECT " + e); } catch (SQLException ex) { mine = "ERR:" + ex.getMessage(); }
        } catch (SQLException cex) { mine = "CONN_FAIL:" + cex.getMessage(); }
        try { want = scalar(bs, "SELECT " + e); } catch (SQLException ex) { want = "ERR:" + ex.getMessage(); }
        boolean ok = normalize(mine).equals(normalize(want));
        System.out.println((ok ? "OK  " : "DIFF") + " " + e + "  mycat=" + mine + "  backend=" + want);
        if (!ok) errs.add(e);
      }
      System.out.println("total=" + exprs.length + " mismatch=" + errs.size());
      System.out.println(errs.isEmpty() ? "VERDICT: PASS" : "VERDICT: RED");
      System.exit(errs.isEmpty() ? 0 : 1);
    }
  }
  static String scalar(Statement s, String sql) throws SQLException {
    try (ResultSet r = s.executeQuery(sql)) { r.next(); return String.valueOf(r.getObject(1)); }
  }
  static String normalize(String v) {
    if (v == null) return "null";
    v = v.trim();
    if (v.endsWith(".0")) v = v.substring(0, v.length() - 2);
    return v;
  }
}
