import com.alibaba.druid.pool.DruidDataSource;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * I-PR-3 round 3: isolates the THIRD-PARTY pinning question that VtSpike cannot answer.
 * VtSpike pre-borrows every connection into a lane, so Druid's saturated-borrow wait is never exercised.
 * Here each op borrows from a pool of `pool` connections under C >> pool concurrency, which is
 * Mycat's real shape (TransactionSessionTemplate:200 / DefaultPrototypeGenerator:108 borrow inside locks).
 * No Mycat lock is present in this probe: any `<== monitors` frame is Druid's or Connector/J's own.
 */
public class DruidPinProbe {

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        int concurrency = Integer.parseInt(args[1]);
        int iterations = Integer.parseInt(args[2]);
        int pool = Integer.parseInt(args[3]);
        int rows = Integer.parseInt(args[4]);
        String url = "jdbc:mysql://127.0.0.1:3306/mysql?serverTimezone=Asia/Shanghai&useUnicode=true"
                + "&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true";

        DruidDataSource ds = new DruidDataSource();
        ds.setUrl(url);
        ds.setUsername("root");
        ds.setPassword("123456");
        ds.setInitialSize(pool);
        ds.setMinIdle(pool);
        ds.setMaxActive(pool);
        ds.setMaxWait(60000);
        ds.setValidationQuery("SELECT 1");
        ds.setTestOnBorrow(false);
        ds.setTestWhileIdle(false);
        ds.init();

        String sql = "WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n < "
                + rows + ") SELECT n, REPEAT('x',64) AS pad FROM seq";
        try (Connection c = ds.getConnection()) {
            jdbc(c, sql);
        }

        ExecutorService exec = mode.equals("vt")
                ? Executors.newVirtualThreadPerTaskExecutor()
                : Executors.newCachedThreadPool();

        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peakInFlight = new AtomicInteger();
        AtomicInteger peakThreads = new AtomicInteger();
        AtomicLong ops = new AtomicLong();
        AtomicLong errors = new AtomicLong();
        ConcurrentLinkedQueue<Long> borrow = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Long> lat = new ConcurrentLinkedQueue<>();
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        Thread sampler = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                peakThreads.accumulateAndGet(tmx.getThreadCount(), Math::max);
                peakInFlight.accumulateAndGet(inFlight.get(), Math::max);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        sampler.setDaemon(true);
        sampler.start();

        int total = concurrency * iterations;
        CountDownLatch done = new CountDownLatch(total);
        long t0 = System.nanoTime();
        for (int t = 0; t < concurrency; t++) {
            exec.execute(() -> {
                for (int i = 0; i < iterations; i++) {
                    inFlight.incrementAndGet();
                    long s = System.nanoTime();
                    try {
                        long b0 = System.nanoTime();
                        Connection c = ds.getConnection();
                        borrow.add(System.nanoTime() - b0);
                        try {
                            jdbc(c, sql);
                        } finally {
                            c.close();
                        }
                        ops.incrementAndGet();
                        lat.add(System.nanoTime() - s);
                    } catch (Throwable e) {
                        if (errors.incrementAndGet() <= 3) {
                            e.printStackTrace();
                        }
                    } finally {
                        inFlight.decrementAndGet();
                        done.countDown();
                    }
                }
            });
        }
        boolean finished = done.await(6, TimeUnit.MINUTES);
        long wallMs = (System.nanoTime() - t0) / 1_000_000;
        sampler.interrupt();
        peakThreads.accumulateAndGet(tmx.getThreadCount(), Math::max);

        long[] l = lat.stream().mapToLong(Long::longValue).sorted().toArray();
        long[] b = borrow.stream().mapToLong(Long::longValue).sorted().toArray();
        exec.shutdownNow();
        ds.close();

        System.out.println("RESULT mode=" + mode + " C=" + concurrency + " iter=" + iterations
                + " rows=" + rows + " pool=" + pool + " finished=" + finished
                + " ops=" + ops.get() + " errors=" + errors.get()
                + " wallMs=" + wallMs
                + " qps=" + String.format("%.1f", ops.get() * 1000.0 / Math.max(1, wallMs))
                + " borrowP50ms=" + String.format("%.2f", pct(b, 50) / 1e6)
                + " borrowP95ms=" + String.format("%.2f", pct(b, 95) / 1e6)
                + " p50ms=" + String.format("%.2f", pct(l, 50) / 1e6)
                + " p95ms=" + String.format("%.2f", pct(l, 95) / 1e6)
                + " p99ms=" + String.format("%.2f", pct(l, 99) / 1e6)
                + " peakPlatformThreads=" + peakThreads.get()
                + " peakInFlight=" + peakInFlight.get());
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }

    static long pct(long[] sorted, int p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    static void jdbc(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            st.setFetchSize(Integer.MIN_VALUE);
            try (ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    rs.getInt(1);
                    rs.getString(2);
                }
            }
        }
    }
}