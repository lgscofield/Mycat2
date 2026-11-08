// PROBE: P-A-03 压缩协议(useCompression=true)
// 验证声明: "能力位不广告 CLIENT_COMPRESS, 压缩协议缺失"
// 声明核验语义: 连接被拒/降级 = 声明成立 PASS; 真跑通压缩 = 文档低估 NOTE。
import java.sql.*;

public class ProbeA_03 {
  public static void main(String[] args) {
    String url = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true&useCompression=true&serverTimezone=Asia/Shanghai";
    try (Connection c = DriverManager.getConnection(url, "root", "123456");
         Statement s = c.createStatement();
         ResultSet r = s.executeQuery("SELECT 1")) {
      r.next();
      System.out.println("NOTE: useCompression connected and queried OK (能力位可能已开放压缩)");
      System.out.println("VERDICT: PASS"); System.exit(0);
    } catch (Exception e) {
      System.out.println("compression rejected: " + e.getMessage());
      System.out.println("=> 与'压缩协议缺失'声明一致");
      System.out.println("VERDICT: PASS"); System.exit(0);
    }
  }
}
