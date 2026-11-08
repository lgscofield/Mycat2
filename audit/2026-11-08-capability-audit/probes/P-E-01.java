// PROBE: P-E-01 微基准: 点查 QPS 量级 + 错误率(冒烟级, 非严格压测)
// 1线程×2000 + 8线程×各500, 分片键点查 + SELECT 1 混合
// PASS = 错误率 0 且 QPS>100(量级健康); QPS 极低或出错如实记录
import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ProbeE_01 {
  public static void main(String[] args) throws Exception {
    String url = "jdbc:mysql://127.0.0.1:8066/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true&cachePrepStmts=true";
    AtomicInteger errors = new AtomicInteger(), done = new AtomicInteger();
    // 预热 + 建表
    try (Connection c = DriverManager.getConnection(url, "root", "123456");
         Statement s = c.createStatement()) {
      s.execute("DROP TABLE IF EXISTS dbaudit_shard.bench");
      s.execute("CREATE TABLE dbaudit_shard.bench (id bigint primary key, v varchar(20)) dbpartition by mod_hash(id) dbpartitions 2");
      try (PreparedStatement p = c.prepareStatement("INSERT INTO dbaudit_shard.bench VALUES (?,?)")) {
        for (long i = 1; i <= 100; i++) { p.setLong(1, i); p.setString(2, "b" + i); p.executeUpdate(); }
      }
    }
    long t0 = System.currentTimeMillis();
    // 1线程 2000 点查
    int N1 = 2000;
    try (Connection c = DriverManager.getConnection(url, "root", "123456");
         PreparedStatement p = c.prepareStatement("SELECT v FROM dbaudit_shard.bench WHERE id=?")) {
      for (int i = 0; i < N1; i++) {
        try { p.setLong(1, (i % 100) + 1); try (ResultSet r = p.executeQuery()) { r.next(); r.getString(1); } done.incrementAndGet(); }
        catch (Exception e) { errors.incrementAndGet(); if (errors.get() < 3) System.out.println("err: " + e.getMessage()); }
      }
    }
    long t1 = System.currentTimeMillis();
    long qps1 = done.get() * 1000 / Math.max(1, t1 - t0);
    System.out.println("1-thread: " + done.get() + " queries in " + (t1 - t0) + "ms => QPS=" + qps1 + " errors=" + errors.get());
    // 8线程 × 500
    done.set(0);
    ExecutorService pool = Executors.newFixedThreadPool(8);
    CountDownLatch latch = new CountDownLatch(8);
    long t2 = System.currentTimeMillis();
    for (int t = 0; t < 8; t++) {
      pool.submit(() -> {
        try (Connection c = DriverManager.getConnection(url, "root", "123456");
             PreparedStatement p = c.prepareStatement("SELECT v FROM dbaudit_shard.bench WHERE id=?")) {
          for (int i = 0; i < 500; i++) {
            try { p.setLong(1, (i % 100) + 1); try (ResultSet r = p.executeQuery()) { r.next(); r.getString(1); } done.incrementAndGet(); }
            catch (Exception e) { errors.incrementAndGet(); }
          }
        } catch (Exception e) { errors.incrementAndGet(); }
        latch.countDown();
      });
    }
    latch.await(120, TimeUnit.SECONDS);
    pool.shutdown();
    long t3 = System.currentTimeMillis();
    long qps8 = done.get() * 1000 / Math.max(1, t3 - t2);
    System.out.println("8-thread: " + done.get() + " queries in " + (t3 - t2) + "ms => QPS=" + qps8 + " errors=" + errors.get());
    try (Connection c = DriverManager.getConnection(url, "root", "123456"); Statement s = c.createStatement()) { s.execute("DROP TABLE dbaudit_shard.bench"); }
    boolean ok = errors.get() == 0 && qps1 > 100 && qps8 > 200;
    System.out.println(ok ? "VERDICT: PASS" : "VERDICT: " + (errors.get() == 0 ? "PASS" : "RED"));
    System.exit(errors.get() == 0 ? 0 : 1);
  }
}
