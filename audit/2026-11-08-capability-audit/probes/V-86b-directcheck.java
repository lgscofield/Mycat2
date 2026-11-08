import java.sql.*;

// 一次性取证: 直连真库, 同 URL 形态(无 characterEncoding)下 character_set_results 是否也 null
public class V86bDirectCheck {
  public static void main(String[] a) throws Exception {
    String url = "jdbc:mysql://127.0.0.1:3306/mysql?useSSL=false&allowPublicKeyRetrieval=true";
    try (Connection c = DriverManager.getConnection(url, "root", "root");
         Statement s = c.createStatement();
         ResultSet r = s.executeQuery("SELECT @@session.character_set_results")) {
      r.next();
      System.out.println("direct-backend character_set_results = " + r.getObject(1));
    }
  }
}
