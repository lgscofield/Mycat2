import com.alibaba.druid.pool.DruidDataSource;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * I-PR-3 throwaway spike. Zero production code change.
 * A/B: cached platform pool (today's IOExecutor.DEFAULT) vs newVirtualThreadPerTaskExecutor.
 * lockKind=sync reproduces NewMycatConnectionImpl's method-level synchronized around blocking JDBC.
 */
public class VtSpike {

    static final class Lane {
        final Connection conn;
        final ReentrantLock lock = new ReentrantLock();

        Lane(Connection c) {
            this.conn = c;
        }
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        String lockKind = args[1];
        int concurrency = Integer.parseInt(args[2]);
        int iterations = Integer.parseInt(args[3]);
        int rows = Integer.parseInt(args[4]);
        int poolSize = Integer.parseInt(args[5]);
        String url = args.length > 6 ? args[6]
                : "jdbc:mysql://127.0.0.1:3306/mysql?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true";

        DruidDataSource ds = new DruidDataSource();
        ds.setUrl(url);
        ds.setUsername("root");
        ds.setPassword("123456");
        ds.setInitialSize(poolSize);
        ds.setMinIdle(poolSize);
        ds.setMaxActive(poolSize);
        ds.setMaxWait(60000);
        ds.setValidationQuery("SELECT 1");
        ds.setTestOnBorrow(false);
        ds.setTestWhileIdle(false);
        ds.init();

        BlockingQueue<Lane> lanes = new LinkedBlockingQueue<>();
        List<Connection> held = new ArrayList<>();
        for (int i = 0; i < poolSize; i++) {
            Connection c = ds.getConnection();
            held.add(c);
            lanes.add(new Lane(c));
        }

        String sql = "WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n < "
                + rows + ") SELECT n, REPEAT('x',64) AS pad FROM seq";

        // warm the lanes so first-run JIT / server-side parse cost does not skew the A/B
        for (Lane l : lanes) {
            jdbc(l.conn, sql);
        }

        ExecutorService exec;
        if (mode.equals("vt")) {
            exec = Executors.newVirtualThreadPerTaskExecutor();
        } else if (mode.equals("bounded")) {
            int max = Integer.getInteger("spike.max", 64);
            int cap = Integer.getInteger("spike.queue", 65535);
            int core = Integer.getInteger("spike.core", 0);
            exec = new java.util.concurrent.ThreadPoolExecutor(core, max, 60, TimeUnit.SECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(cap),
                    Executors.defaultThreadFactory(),
                    new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        } else {
            exec = Executors.newCachedThreadPool();
        }

        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peakInFlight = new AtomicInteger();
        AtomicInteger peakThreads = new AtomicInteger();
        AtomicLong ops = new AtomicLong();
        AtomicLong errors = new AtomicLong();
        ConcurrentLinkedQueue<Long> lat = new ConcurrentLinkedQueue<>();
        // submit->start delay: pinned VTs queue in the ForkJoinPool BEFORE the task body runs,
        // so `lat` alone hides that wait. e2e = startDelay + lat is the comparable number.
        ConcurrentLinkedQueue<Long> startDelay = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Long> e2e = new ConcurrentLinkedQueue<>();
        AtomicLong rejected = new AtomicLong();
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();

        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
        sampler.scheduleAtFixedRate(() -> {
            peakThreads.accumulateAndGet(tmx.getThreadCount(), Math::max);
            peakInFlight.accumulateAndGet(inFlight.get(), Math::max);
        }, 0, 20, TimeUnit.MILLISECONDS);

        int total = concurrency * iterations;
        CountDownLatch done = new CountDownLatch(total);
        long t0 = System.nanoTime();
        for (int t = 0; t < concurrency; t++) {
            final long submitted = System.nanoTime();
            try {
                exec.execute(() -> {
                    final long started = System.nanoTime();
                    startDelay.add(started - submitted);
                    for (int i = 0; i < iterations; i++) {
                        int cur = inFlight.incrementAndGet();
                        peakInFlight.accumulateAndGet(cur, Math::max);
                        long s = System.nanoTime();
                        Lane lane = null;
                        try {
                            lane = lanes.take();
                            runQuery(lane, lockKind, sql);
                            ops.incrementAndGet();
                            long d = System.nanoTime() - s;
                            lat.add(d);
                            e2e.add((started - submitted) + d);
                        } catch (Throwable e) {
                            if (errors.incrementAndGet() <= 3) {
                                e.printStackTrace();
                            }
                        } finally {
                            if (lane != null) {
                                lanes.add(lane);
                            }
                            inFlight.decrementAndGet();
                            done.countDown();
                        }
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException rex) {
                rejected.incrementAndGet();
                for (int i = 0; i < iterations; i++) {
                    done.countDown();
                }
            }
        }
        boolean finished = done.await(6, TimeUnit.MINUTES);
        long wallMs = (System.nanoTime() - t0) / 1_000_000;
        sampler.shutdownNow();
        peakThreads.accumulateAndGet(tmx.getThreadCount(), Math::max);

        long[] l = lat.stream().mapToLong(Long::longValue).sorted().toArray();
        long[] sd = startDelay.stream().mapToLong(Long::longValue).sorted().toArray();
        long[] ee = e2e.stream().mapToLong(Long::longValue).sorted().toArray();
        exec.shutdownNow();
        for (Connection c : held) {
            try {
                c.close();
            } catch (Exception ignore) {
            }
        }
        ds.close();

        System.out.println("RESULT mode=" + mode + " lock=" + lockKind
                + " C=" + concurrency + " iter=" + iterations + " rows=" + rows + " pool=" + poolSize
                + " finished=" + finished
                + " ops=" + ops.get() + " errors=" + errors.get()
                + " wallMs=" + wallMs
                + " qps=" + String.format("%.1f", ops.get() * 1000.0 / Math.max(1, wallMs))
                + " p50ms=" + String.format("%.2f", pct(l, 50) / 1e6)
                + " p95ms=" + String.format("%.2f", pct(l, 95) / 1e6)
                + " p99ms=" + String.format("%.2f", pct(l, 99) / 1e6)
                + " peakPlatformThreads=" + peakThreads.get()
                + " peakInFlight=" + peakInFlight.get()
                + " rejected=" + rejected.get()
                + " startP50ms=" + String.format("%.2f", pct(sd, 50) / 1e6)
                + " startP95ms=" + String.format("%.2f", pct(sd, 95) / 1e6)
                + " e2eP50ms=" + String.format("%.2f", pct(ee, 50) / 1e6)
                + " e2eP95ms=" + String.format("%.2f", pct(ee, 95) / 1e6)
                + " e2eP99ms=" + String.format("%.2f", pct(ee, 99) / 1e6));
        System.out.flush();
        // VT scheduler / Druid non-daemon threads can linger; force exit so the runner is deterministic
        Runtime.getRuntime().halt(0);
    }

    static long pct(long[] sorted, int p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    static void runQuery(Lane lane, String lockKind, String sql) throws Exception {
        if (lockKind.equals("sync")) {
            synchronized (lane) {
                jdbc(lane.conn, sql);
            }
        } else if (lockKind.equals("reentrant")) {
            lane.lock.lock();
            try {
                jdbc(lane.conn, sql);
            } finally {
                lane.lock.unlock();
            }
        } else {
            jdbc(lane.conn, sql);
        }
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