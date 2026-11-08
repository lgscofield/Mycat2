// PROBE: P-A-01 server-side 预处理语句(useServerPrepStmts=true) CRUD 往返
// 验证: "COM_STMT_PREPARE 为 Druid AST 重渲染模拟而非二进制透传"声明下的真实可用性
// PASS = prepare/execute/参数类型(int/varchar/null/date/blob)全往返一致
import java.sql.*;
import java.util.*;

public class ProbeA_01 {
  public static void main(String[] args) throws Exception {
    String url = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true&useServerPrepStmts=true&serverTimezone=Asia/Shanghai";
    List<String> errs = new ArrayList<>();
    try (Connection c = DriverManager.getConnection(url, "root", "123456");
         Statement st = c.createStatement()) {
      st.execute("DROP TABLE IF EXISTS pa01_t");
      st.execute("CREATE TABLE pa01_t (id int primary key, name varchar(64), amt decimal(10,2), dt datetime, bin varbinary(32))");
      try (PreparedStatement p = c.prepareStatement("INSERT INTO pa01_t VALUES (?,?,?,?,?)")) {
        p.setInt(1, 1); p.setString(2, "张三-中文字符"); p.setBigDecimal(3, new java.math.BigDecimal("99.99"));
        p.setTimestamp(4, Timestamp.valueOf("2026-11-08 10:20:30")); p.setBytes(5, new byte[]{1,2,3,(byte)0xFF});
        p.executeUpdate();
        p.setInt(1, 2); p.setString(2, null); p.setBigDecimal(3, null); p.setTimestamp(4, null); p.setBytes(5, null);
        p.executeUpdate();
      }
      try (PreparedStatement q = c.prepareStatement("SELECT * FROM pa01_t WHERE id = ?")) {
        q.setInt(1, 1);
        try (ResultSet r = q.executeQuery()) {
          r.next();
          if (!"张三-中文字符".equals(r.getString("name"))) errs.add("varchar中文失配: " + r.getString("name"));
          if (r.getBigDecimal("amt").doubleValue() != 99.99) errs.add("decimal失配");
          if (!"2026-11-08 10:20:30.0".equals(String.valueOf(r.getTimestamp("dt")))) errs.add("datetime失配: " + r.getTimestamp("dt"));
          byte[] b = r.getBytes("bin");
          if (b == null || b.length != 4 || b[3] != (byte)0xFF) errs.add("varbinary失配");
        }
        q.setInt(1, 2);
        try (ResultSet r = q.executeQuery()) { r.next();
          if (r.getString("name") != null || r.getBytes("bin") != null) errs.add("NULL往返失配");
        }
      }
      try (PreparedStatement u = c.prepareStatement("UPDATE pa01_t SET name=? WHERE id=?")) {
        u.setString(1, "updated"); u.setInt(2, 1); u.executeUpdate();
      }
      try (PreparedStatement d = c.prepareStatement("DELETE FROM pa01_t WHERE id=?")) {
        d.setInt(1, 2); d.executeUpdate();
      }
      try (ResultSet r = st.executeQuery("SELECT COUNT(*) FROM pa01_t")) { r.next();
        if (r.getInt(1) != 1) errs.add("count=" + r.getInt(1));
      }
      st.execute("DROP TABLE pa01_t");
    } catch (Exception e) {
      System.out.println("EXCEPTION: " + e);
      System.out.println("VERDICT: RED"); System.exit(1);
    }
    if (errs.isEmpty()) { System.out.println("VERDICT: PASS"); System.exit(0); }
    else { errs.forEach(System.out::println); System.out.println("VERDICT: RED"); System.exit(1); }
  }
}
