import java.sql.*;

// F9 复核: createDataSource hint 以"纯 hint+分号 + Statement.execute()"形态(他们测试的原样用法)是否可解析
public class VF9Recheck {
  public static void main(String[] a) throws Exception {
    String url = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    try (Connection c = DriverManager.getConnection(url, "root", "123456");
         Statement s = c.createStatement()) {
      String hint = "/*+ mycat:createDataSource{\"name\":\"probeDs9\",\"url\":\"jdbc:mysql://127.0.0.1:3306/dbaudit_b?useSSL=false&allowPublicKeyRetrieval=true\",\"user\":\"audit\",\"password\":\"Audit#2026\"} */;";
      try {
        boolean hasRs = s.execute(hint);
        System.out.println("execute ok, hasResultSet=" + hasRs);
      } catch (SQLException e) {
        System.out.println("EXECUTE-ERR: " + e.getMessage());
      }
      // 验证落盘
      boolean found = false;
      try (ResultSet r = s.executeQuery("/*+ mycat:showDataSources{} */;")) {
        while (r.next()) { if ("probeDs9".equals(r.getString(1))) { found = true; System.out.println("probeDs9 visible: PASSWORD=[" + r.getString(3) + "]"); } }
      }
      System.out.println(found ? "F9-RECHECK: createDataSource 可达(F9 需重定性)" : "F9-RECHECK: 仍不可达");
      // 清理
      try { s.execute("/*+ mycat:dropDataSource{\"name\":\"probeDs9\"} */;"); } catch (SQLException ignore) {}
    }
  }
}
