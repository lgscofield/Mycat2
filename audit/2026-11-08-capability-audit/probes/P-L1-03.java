// PROBE: P-L1-03 前端 TLS(REQUIRED) 连接
// mycat 本次启动未配证书 → 预期回落自签(dev 默认)或握手失败。
// 判定: sslMode=REQUIRED 且能连上并完成查询 = PASS(TLS 真实生效, 事后用 cipher 判据复核);
//       连接被拒 = RED(TLS 面破坏); 其他记为部分。
import java.sql.*;

public class ProbeL1_03 {
  public static void main(String[] args) {
    String url = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?sslMode=REQUIRED&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&tlsVersions=TLSv1.2,TLSv1.3";
    try (Connection c = DriverManager.getConnection(url, "root", "123456");
         Statement s = c.createStatement()) {
      ResultSet r = s.executeQuery("SELECT 1");
      r.next();
      boolean ok = r.getInt(1) == 1;
      String cipher = null;
      try (ResultSet rs = s.executeQuery("SHOW STATUS LIKE 'Ssl_cipher'")) {
        rs.next(); cipher = rs.getString(2);
      } catch (Exception ignore) { /* 代理可能不透出 */ }
      System.out.println("TLS REQUIRED connect OK; Ssl_cipher=" + cipher);
      System.out.println(ok ? "VERDICT: PASS" : "VERDICT: RED");
      System.exit(ok ? 0 : 1);
    } catch (Exception e) {
      System.out.println("TLS REQUIRED connect FAILED: " + e.getMessage());
      System.out.println("VERDICT: RED");
      System.exit(1);
    }
  }
}
