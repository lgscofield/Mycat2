// V-86 活体验收: 净室启动(缺 clusters/)后 Connector/J 可连(I-DEBT-86)
// 前置: 独立 mycat-home(仅 datasources+users) 起 8067
// PASS = Connector/J 连接成功(自动发 22 变量引导查询) + SELECT 1 + @@session.max_allowed_packet 可读
import java.sql.*;

public class V86Cleanroom {
  public static void main(String[] args) throws Exception {
    String url = "jdbc:mysql://127.0.0.1:8067/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    try (Connection c = DriverManager.getConnection(url, "root", "123456");
         Statement s = c.createStatement()) {
      try (ResultSet r = s.executeQuery("SELECT 1")) { r.next(); if (r.getInt(1) != 1) throw new SQLException("SELECT 1 fail"); }
      try (ResultSet r = s.executeQuery("SELECT @@session.max_allowed_packet")) {
        r.next();
        System.out.println("@@session.max_allowed_packet = " + r.getObject(1));
      }
      try (ResultSet r = s.executeQuery("SELECT @@session.auto_increment_increment, @@session.sql_mode")) {
        r.next();
        System.out.println("auto_increment_increment=" + r.getObject(1) + " sql_mode=" + r.getObject(2));
      }
      System.out.println("净室启动 Connector/J 连通 OK");
      System.out.println("VERDICT: PASS"); System.exit(0);
    } catch (Exception e) { System.out.println("EXCEPTION: " + e); System.out.println("VERDICT: RED"); System.exit(1); }
  }
}
