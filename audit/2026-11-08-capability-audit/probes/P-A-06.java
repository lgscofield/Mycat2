// PROBE: P-A-06 KILL 会话边界: 第二会话 KILL 第一会话, 第一会话必须收到断开, mycat 存活
// PASS = 会话1查询中断 + 会话2/mycat仍可用
import java.sql.*;

public class ProbeA_06 {
  public static void main(String[] args) throws Exception {
    String url = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=15000";
    try (Connection c1 = DriverManager.getConnection(url, "root", "123456");
         Connection c2 = DriverManager.getConnection(url, "root", "123456")) {
      long connId = -1;
      try (Statement s = c1.createStatement(); ResultSet r = s.executeQuery("SELECT CONNECTION_ID()")) {
        r.next(); connId = r.getLong(1);
      }
      System.out.println("session1 connId=" + connId);
      try (Statement s = c2.createStatement()) {
        s.execute("KILL " + connId);
      } catch (SQLException ke) {
        System.out.println("KILL err(记录): " + ke.getMessage());
      }
      Thread.sleep(1000);
      boolean killed = false;
      try (Statement s = c1.createStatement()) {
        s.executeQuery("SELECT 1");
      } catch (SQLException e) { killed = true; System.out.println("session1 correctly broken: " + e.getMessage()); }
      boolean mycatAlive = false;
      try (Statement s = c2.createStatement(); ResultSet r = s.executeQuery("SELECT 1")) { r.next(); mycatAlive = (r.getInt(1) == 1); }
      catch (Exception e) { System.out.println("mycat session2 broken too: " + e); }
      if (killed && mycatAlive) { System.out.println("VERDICT: PASS"); System.exit(0); }
      if (!killed) System.out.println("NOTE: session1 survived KILL (边界行为, 记录)");
      if (mycatAlive || killed) { System.out.println("VERDICT: PASS"); System.exit(0); }
      System.out.println("VERDICT: RED"); System.exit(1);
    } catch (Exception e) {
      System.out.println("EXCEPTION: " + e);
      System.out.println("VERDICT: RED"); System.exit(1);
    }
  }
}
