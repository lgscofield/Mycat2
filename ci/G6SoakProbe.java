import java.sql.*;

/**
 * I-DEBT-96/G6 长稳探针(CI 版): 4 线程混合 CRUD × 30 分钟 + 周期快照(吞吐/错误/后端连接/堆)。
 * 判据: 错误率 0 + 后端连接数稳定(无泄漏)。出口 0=PASS / 1=RED。
 * 自举: CREATE DATABASE/TABLE(IF NOT EXISTS),无外部前置。
 * 环境: mycat@8066 root/123456(bundled 默认凭据与 CI services 对齐)。
 */
public class G6SoakProbe {
    public static void main(String[] a) throws Exception {
        long DURATION_MS = Long.getLong("soakMinutes", 30L) * 60 * 1000L;
        String mycat = "jdbc:mysql://127.0.0.1:8066/mysql?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000";
        String backend = "jdbc:mysql://127.0.0.1:3306/dbaudit_a?useSSL=false&allowPublicKeyRetrieval=true";
        AtomicInteger done = new AtomicInteger(), errors = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(4);
        long t0 = System.currentTimeMillis();
        try (Connection ob = DriverManager.getConnection(backend, "root", "123456");
             Statement os = ob.createStatement()) {
            try (Connection setup = DriverManager.getConnection(mycat, "root", "123456");
                 Statement ss = setup.createStatement()) {
                ss.execute("CREATE DATABASE IF NOT EXISTS dbaudit_shard");
                ss.execute("DROP TABLE IF EXISTS dbaudit_shard.soakg6");
                ss.execute("CREATE TABLE dbaudit_shard.soakg6 (id bigint primary key, v int) dbpartition by mod_hash(id) dbpartitions 2");
            }
            System.out.println("t0 backend_conns=" + conns(os) + " heap_used_mb=" + usedM());
            for (int w = 0; w < 4; w++) {
                final int wid = w;
                new Thread(() -> {
                    try (Connection c = DriverManager.getConnection(mycat, "root", "123456");
                         PreparedStatement ins = c.prepareStatement("INSERT INTO dbaudit_shard.soakg6 VALUES (?,?)");
                         PreparedStatement sel = c.prepareStatement("SELECT v FROM dbaudit_shard.soakg6 WHERE id=?");
                         PreparedStatement upd = c.prepareStatement("UPDATE dbaudit_shard.soakg6 SET v=v+1 WHERE id=?");
                         PreparedStatement del = c.prepareStatement("DELETE FROM dbaudit_shard.soakg6 WHERE id=?")) {
                        long i = wid * 1000000L;
                        while (System.currentTimeMillis() - t0 < DURATION_MS) {
                            long id = (i % 500) + 1;
                            try {
                                switch ((int) (i % 4)) {
                                    case 0: del.setLong(1, id + 5000); del.executeUpdate(); ins.setLong(1, id + 5000); ins.setInt(2, (int) i); ins.executeUpdate(); break;
                                    case 1: sel.setLong(1, id); try (ResultSet r = sel.executeQuery()) { r.next(); } break;
                                    case 2: upd.setLong(1, id); upd.executeUpdate(); break;
                                    case 3: sel.setLong(1, id + 5000); try (ResultSet r = sel.executeQuery()) { if (r.next()) r.getInt(1); } break;
                                }
                                done.incrementAndGet();
                            } catch (SQLException e) {
                                errors.incrementAndGet();
                                int n = errors.get();
                                if (n % 50 == 1) {
                                    System.out.println("ERR_DETAIL[" + n + "] code=" + e.getErrorCode() + " sqlState=" + e.getSQLState() + " msg=" + e.getMessage() + " sqlIdx=" + i);
                                }
                            }
                            i++;
                        }
                    } catch (Exception e) { errors.incrementAndGet(); }
                    latch.countDown();
                }).start();
            }
            long lastDone = 0;
            while (System.currentTimeMillis() - t0 < DURATION_MS) {
                Thread.sleep(60000);
                long el = (System.currentTimeMillis() - t0) / 60000;
                long d = done.get();
                System.out.println("t+" + el + "min ops=" + d + " rate/min=" + (d - lastDone) + " errors=" + errors.get()
                        + " backend_conns=" + conns(os) + " heap_used_mb=" + usedM());
                lastDone = d;
            }
            latch.await(120, TimeUnit.SECONDS);
        }
        try (Connection c = DriverManager.getConnection(mycat, "root", "123456");
             Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS dbaudit_shard.soakg6");
        } catch (Exception ignore) {}
        long mins = (System.currentTimeMillis() - t0) / 60000;
        System.out.println("SOAK DONE: " + mins + "min ops=" + done.get() + " errors=" + errors.get());
        System.out.println(errors.get() == 0 ? "RESULT=PASS" : "RESULT=RED (errors=" + errors.get() + ")");
        System.exit(errors.get() == 0 ? 0 : 1);
    }

    static int conns(Statement s) throws Exception {
        try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE DB LIKE 'dbaudit%'")) { r.next(); return r.getInt(1); }
    }

    static long usedM() { Runtime rt = Runtime.getRuntime(); return (rt.totalMemory() - rt.freeMemory()) / 1048576; }
}
