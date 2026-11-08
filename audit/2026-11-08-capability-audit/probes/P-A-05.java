// PROBE: P-A-05 long data(setBinaryStream 大对象流式上传, 走 COM_STMT_SEND_LONG_DATA 路径)
// PASS = 1MB blob 经流式上传后逐字节往返一致
import java.io.*;
import java.sql.*;
import java.util.*;

public class ProbeA_05 {
  public static void main(String[] args) throws Exception {
    String url = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true&useServerPrepStmts=true&serverTimezone=Asia/Shanghai";
    byte[] blob = new byte[1024 * 1024];
    new Random(42).nextBytes(blob);
    try (Connection c = DriverManager.getConnection(url, "root", "123456");
         Statement st = c.createStatement()) {
      st.execute("DROP TABLE IF EXISTS pa05_t");
      st.execute("CREATE TABLE pa05_t (id int primary key, data longblob)");
      try (PreparedStatement p = c.prepareStatement("INSERT INTO pa05_t VALUES (?,?)")) {
        p.setInt(1, 1);
        p.setBinaryStream(2, new ByteArrayInputStream(blob), blob.length);
        p.executeUpdate();
      }
      try (ResultSet r = st.executeQuery("SELECT data FROM pa05_t WHERE id=1")) {
        r.next();
        byte[] got = r.getBytes(1);
        if (Arrays.equals(blob, got)) { System.out.println("1MB blob roundtrip OK (" + got.length + " bytes)");
          st.execute("DROP TABLE pa05_t");
          System.out.println("VERDICT: PASS"); System.exit(0); }
        else { System.out.println("blob mismatch: want " + blob.length + " got " + (got == null ? "null" : got.length));
          System.out.println("VERDICT: RED"); System.exit(1); }
      }
    } catch (Exception e) {
      System.out.println("EXCEPTION: " + e);
      System.out.println("VERDICT: RED"); System.exit(1);
    }
  }
}
