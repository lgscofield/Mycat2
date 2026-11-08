// V-88 活体验收: showDataSources 不再明文回显 PASSWORD(I-DEBT-88)
// PASS = 所有行 PASSWORD 列 == "******"(null 保持 null);其余列形状不变
import java.sql.*;

public class V88Mask {
  public static void main(String[] args) throws Exception {
    String url = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
    try (Connection c = DriverManager.getConnection(url, "root", "123456");
         Statement s = c.createStatement()) {
      // Connector/J 对纯 hint 语句有客户端静态分类守卫(executeQuery 会误判"不产结果集"),
      // 沿用 MycatTest.executeQuery 同款规避: Statement.execute() + getResultSet()
      boolean hasRs = s.execute("/*+ mycat:showDataSources{} */;");
      if (!hasRs) { System.out.println("no result set"); System.out.println("VERDICT: RED"); System.exit(1); }
      ResultSet r = s.getResultSet();
      ResultSetMetaData md = r.getMetaData();
      int pwdIdx = -1;
      for (int i = 1; i <= md.getColumnCount(); i++) if ("PASSWORD".equalsIgnoreCase(md.getColumnLabel(i))) pwdIdx = i;
      if (pwdIdx < 0) { System.out.println("PASSWORD column missing!"); System.out.println("VERDICT: RED"); System.exit(1); }
      int rows = 0, leaked = 0;
      while (r.next()) {
        rows++;
        Object v = r.getObject(pwdIdx);
        String s2 = String.valueOf(v);
        System.out.println("row" + rows + " NAME=" + r.getObject(1) + " PASSWORD=[" + s2 + "]");
        if (v != null && !"******".equals(s2)) leaked++;
      }
      if (rows > 0 && leaked == 0) { System.out.println("VERDICT: PASS (rows=" + rows + " 全脱敏)"); System.exit(0); }
      System.out.println("leaked=" + leaked + " rows=" + rows);
      System.out.println("VERDICT: RED"); System.exit(1);
    } catch (Exception e) { System.out.println("EXCEPTION: " + e); System.out.println("VERDICT: RED"); System.exit(1); }
  }
}
