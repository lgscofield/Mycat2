package io.mycat;

import io.mycat.beans.mycat.MycatErrorCode;
import io.mycat.commands.DefaultCommandHandler;
import io.mycat.config.MycatServerConfig;
import io.mycat.config.ThreadPoolExecutorConfig;
import io.mycat.proxy.session.MySQLServerSession;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * I-PR-1 Task 5：前端准入闸的「真接线」与拒绝语义回归锁。
 *
 * <p>三件事各自独立可红：
 * （1）MycatCore 是否把**配置**构造的 IOExecutor 注册进那份不被整表 register 抹掉的 context
 * （I-VE-1 教训：注册错 map ⇒ 启动后被抹掉 ⇒ 取用点拿到 DEFAULT）；
 * （2）拒绝是否只失败 Future、不关通道、不写任何字节（I-DEBT-84 教训：列头已刷后裸 ERR 非法）；
 * （3）车道 U 的 25 处内层/释放/后台腿是否仍走永不拒绝的 executeBlocking（加闸即饱和死锁）。
 *
 * <p>不连真库、不起监听端口：MycatCore 构造器只装配 context，startServer() 才绑端口。
 */
public class FrontendAdmissionWiringTest {

    private static final long AWAIT_SECONDS = 10;

    private Map<Class, Object> savedContext;
    private String savedHome;
    private String savedMode;
    private final List<CountDownLatch> releases = new ArrayList<>();
    private final List<Vertx> openedVertx = new ArrayList<>();
    private final List<Path> tempHomes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        savedContext = MetaClusterCurrent.copyContext();
        savedHome = System.getProperty("MYCAT_HOME");
        savedMode = System.getProperty("mode");
    }

    @AfterEach
    void tearDown() throws Exception {
        for (CountDownLatch release : releases) {
            release.countDown();
        }
        for (Vertx vertx : openedVertx) {
            vertx.close().toCompletionStage().toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS);
        }
        MetaClusterCurrent.register(savedContext);
        restore("MYCAT_HOME", savedHome);
        restore("mode", savedMode);
        for (Path home : tempHomes) {
            deleteRecursively(home);
        }
    }

    /**
     * 钉住 MycatCore:135 真接线：maxPoolSize=7 ⇒ 注册进 context 的 IOExecutor 许可上界就是 7；
     * admitTimeoutMillis=50（非默认 200）⇒ 拒绝前等待量级 ≥50ms 且 <200ms。
     * 两个旋钮都用非默认值，硬编码 DEFAULT 或硬编码 200 都过不了。
     */
    @Test
    public void mycatCoreRegistersConfiguredIOExecutor() throws Exception {
        MycatServerConfig config = productionServerConfig();
        config.getServer().getWorkerPool().setMaxPoolSize(7);
        config.getServer().getWorkerPool().setAdmitTimeoutMillis(50);
        bootMycatCoreWith(config);

        IOExecutor registered = MetaClusterCurrent.wrapper(IOExecutor.class);
        assertNotSame(IOExecutor.DEFAULT, registered,
                "MycatCore 必须注册由配置构造的实例，不得仍是 IOExecutor.DEFAULT 兜底");

        CountDownLatch release = saturate(registered, 7);
        long start = System.nanoTime();
        Throwable cause = awaitFailure(registered.executeAdmitted(p -> p.tryComplete()), 3);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertIoLimit(cause);
        assertTrue(elapsedMs >= 50, "admitTimeoutMillis=50 须真生效，实得 " + elapsedMs + "ms");
        assertTrue(elapsedMs < 200, "不得是硬编码的默认 200ms，实得 " + elapsedMs + "ms");
        release.countDown();
    }

    /** A3：拒绝只失败 Future，通道必须留着，且闸放行后下一条语句真能进入 body。 */
    @Test
    public void admissionRejectionKeepsSessionAlive() throws Exception {
        IOExecutor real = IOExecutor.create(new IOExecutorConfig(1, 0));
        CountingExecutor counting = new CountingExecutor(real);
        MetaClusterCurrent.register(IOExecutor.class, counting);
        MySQLServerSession session = Mockito.mock(MySQLServerSession.class);
        CountDownLatch release = saturate(real, 1);

        DefaultCommandHandler handler = new DefaultCommandHandler();
        Throwable cause = awaitFailure(handler.handleQuery(sql("select 1"), session), 3);
        assertIoLimit(cause);
        assertEquals(0, counting.admittedBodies.get(), "被拒的语句不得进入 body");
        Mockito.verify(session, Mockito.never()).close(Mockito.anyBoolean(), Mockito.anyString());

        release.countDown();
        awaitCount(real, 0);
        settle(handler.handleQuery(sql("select 1"), session), 5);
        assertEquals(1, counting.admittedBodies.get(),
                "许可释放后下一条语句必须被放行并真进入 body（会话未被拒绝路径打死）");
        Mockito.verify(session, Mockito.never()).close(Mockito.anyBoolean(), Mockito.anyString());
    }

    /** 协议合法性：拒绝发生在任何字节写出之前，receiver 根本没被构造，合法 ERR 留给外层 recover。 */
    @Test
    public void rejectionHappensBeforeAnyByteWritten() throws Exception {
        IOExecutor real = IOExecutor.create(new IOExecutorConfig(1, 0));
        CountingExecutor counting = new CountingExecutor(real);
        MetaClusterCurrent.register(IOExecutor.class, counting);
        MySQLServerSession session = Mockito.mock(MySQLServerSession.class);
        CountDownLatch release = saturate(real, 1);

        Throwable cause = awaitFailure(new DefaultCommandHandler().handleQuery(sql("select 1"), session), 3);
        assertIoLimit(cause);
        assertEquals(0, counting.admittedBodies.get(), "body 未运行 ⇒ ProxyReceiverImpl 未构造 ⇒ 不可能已 flush");
        for (Invocation invocation : Mockito.mockingDetails(session).getInvocations()) {
            String name = invocation.getMethod().getName();
            assertFalse(name.startsWith("write") || name.equals("close"),
                    "拒绝路径上不得有任何写/关动作，实得 " + name);
        }
        release.countDown();
    }

    /**
     * A8 强化 / 用户「必须可配置」条件：两个非默认值都测。
     * 只测一个仍可能被「硬编码 200 + 巧合」蒙混。
     */
    @Test
    public void admitTimeoutIsConfigurableNotHardcoded() throws Exception {
        long shortWait = rejectionWaitMillis(50);
        assertTrue(shortWait >= 50, "admitTimeoutMillis=50 ⇒ 等待须 ≥50ms，实得 " + shortWait);
        assertTrue(shortWait < 200, "admitTimeoutMillis=50 ⇒ 不得等到默认 200ms，实得 " + shortWait);

        long longWait = rejectionWaitMillis(400);
        assertTrue(longWait >= 400, "admitTimeoutMillis=400 ⇒ 等待须 ≥400ms，实得 " + longWait);
    }

    /**
     * 车道 U：内层腿、释放腿、后台腿一律不得经闸。给它们加闸 ⇒ 一条客户端语句嵌套 3~4 层，
     * 饱和时外层持许可等内层、内层等许可 ⇒ 永久死锁（IOExecutorAdmissionTest 有反向对照实证）。
     */
    @Test
    public void innerLegsStillUseExecuteBlocking() throws Exception {
        Path root = repoRoot();
        List<Path> sources = mainSources(root);
        assertFalse(sources.isEmpty(), "源码扫描没扫到任何文件，断言会是空转");

        List<String> gated = new ArrayList<>();
        for (Path file : sources) {
            String text = read(file);
            if (text.contains("executeAdmitted")) {
                gated.add(relative(root, file));
            }
        }
        String gate = "common/src/main/java/io/mycat/IOExecutor.java";
        String handler = "server/src/main/java/io/mycat/commands/DefaultCommandHandler.java";
        String frontend = "server/src/main/java/io/mycat/mycatmysql/MycatVertxMySQLHandler.java";
        List<String> allowList = List.of(gate, handler, frontend);
        assertEquals(allowList, gated.stream().sorted().collect(Collectors.toList()),
                "只有闸本体与两个前端入口文件可出现 executeAdmitted，其余一律不得经闸");

        assertEquals(3, count(read(root.resolve(frontend)), "executeAdmitted"),
                "COM_QUERY / COM_STMT_PREPARE / COM_STMT_EXECUTE 三处");
        assertEquals(2, count(read(root.resolve(handler)), "executeAdmitted"),
                "handleQuery / handlePrepareStatementExecute 两处");

        for (String lane : LANE_U) {
            String text = read(root.resolve(lane));
            assertTrue(text.contains("executeBlocking"), "车道 U 的腿必须仍走 executeBlocking: " + lane);
            assertFalse(text.contains("executeAdmitted"), "车道 U 的腿被误接闸: " + lane);
        }
    }

    /**
     * A7 反地雷：spike 里打出 inFlight=1 的那组生产值（core1/max1024/maxPending65535/taskTimeout5MINUTES）。
     * 旧 TPE 下 core=1 + 无界队列 ⇒ 任务排队串行；VT 无队列 ⇒ 必须真并发。
     *
     * <p>注意：peakInFlight() 在**提交**时累加，单看它 >1 是空断言（串行池也能刷出 32）。
     * 故用 32 路 barrier 钉「同时身处 body 之内」——串行实现下这个 latch 永不归零。
     */
    @Test
    public void productionServerJsonValuesDoNotSerialize() throws Exception {
        ThreadPoolExecutorConfig workerPool = productionServerConfig().getServer().getWorkerPool();
        assertEquals(1, workerPool.getCorePoolSize(), "必须是 spike 那组生产值");
        assertEquals(1024, workerPool.getMaxPoolSize());
        assertEquals(65535, workerPool.getMaxPendingLimit());
        assertEquals(5, workerPool.getTaskTimeout());
        assertEquals("MINUTES", workerPool.getTimeUnit());

        IOExecutor ex = IOExecutor.create(IOExecutorConfig.from(workerPool));
        int n = 32;
        CountDownLatch allInside = new CountDownLatch(n);
        CountDownLatch release = new CountDownLatch(1);
        releases.add(release);
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            ex.executeAdmitted(p -> {
                maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                allInside.countDown();
                try {
                    release.await(AWAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                inside.decrementAndGet();
                p.tryComplete();
            });
        }
        assertTrue(allInside.await(AWAIT_SECONDS, TimeUnit.SECONDS),
                "生产 server.json 值下 " + n + " 条必须真并发进入 body；串行实现下此 latch 永不归零");
        assertTrue(ex.peakInFlight() > 1, "peakInFlight 必须 >1，实得 " + ex.peakInFlight());
        assertEquals(n, maxInside.get(), "同时在飞峰值须达 " + n);
        release.countDown();
        awaitCount(ex, 0);
    }

    // ---- 车道 U 的 13 个文件（25 处 executeBlocking）----

    private static final List<String> LANE_U = List.of(
            "datasource/src/main/java/io/mycat/datasource/jdbc/datasource/JdbcConnectionManager.java",
            "plug/src/main/java/io/mycat/plug/sequence/DefaultPrototypeGenerator.java",
            "router/src/main/java/io/mycat/router/custom/HttpCustomRuleFunction.java",
            "server/src/main/java/io/mycat/vertxmycat/SqlResultCollectingPrepareJdbcQuery.java",
            "server/src/main/java/io/mycat/vertxmycat/RowSetJdbcPreparedJdbcQuery.java",
            "server/src/main/java/io/mycat/vertxmycat/JdbcMySqlConnection.java",
            "server/src/main/java/io/mycat/monitor/ThreadMycatConnectionImplWrapper.java",
            "server/src/main/java/io/mycat/monitor/MycatSQLLogMonitorImpl.java",
            "server/src/main/java/io/mycat/commands/SqlResultSetService.java",
            "server/src/main/java/io/mycat/commands/ReceiverImpl.java",
            "server/src/main/java/io/mycat/exporter/SqlRecorderRuntime.java",
            "server/src/main/java/io/mycat/exporter/MySQLLogConsumer.java",
            "server/src/main/java/io/mycat/sqlhandler/ddl/CreateTableSQLHandler.java");

    // ---- 测试替身与工具 ----

    /** 只统计「闸放行后真进入 body」的次数；许可仍由底下那个真闸把守。 */
    static final class CountingExecutor implements IOExecutor {
        final IOExecutor delegate;
        final AtomicInteger admittedBodies = new AtomicInteger();

        CountingExecutor(IOExecutor delegate) {
            this.delegate = delegate;
        }

        @Override
        public <T> Future<T> executeBlocking(Handler<Promise<T>> blockingCodeHandler) {
            return delegate.executeBlocking(blockingCodeHandler);
        }

        @Override
        public <T> Future<T> executeAdmitted(Handler<Promise<T>> blockingCodeHandler) {
            return delegate.executeAdmitted(p -> {
                admittedBodies.incrementAndGet();
                blockingCodeHandler.handle(p);
            });
        }

        @Override
        public long count() {
            return delegate.count();
        }

        @Override
        public long peakInFlight() {
            return delegate.peakInFlight();
        }
    }

    private long rejectionWaitMillis(long admitTimeoutMillis) throws Exception {
        IOExecutor real = IOExecutor.create(new IOExecutorConfig(1, admitTimeoutMillis));
        MetaClusterCurrent.register(IOExecutor.class, real);
        MySQLServerSession session = Mockito.mock(MySQLServerSession.class);
        CountDownLatch release = saturate(real, 1);
        long start = System.nanoTime();
        Throwable cause = awaitFailure(new DefaultCommandHandler().handleQuery(sql("select 1"), session), 5);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertIoLimit(cause);
        release.countDown();
        awaitCount(real, 0);
        return elapsedMs;
    }

    /** 占满 n 个许可：latch 归零即证明 n 个 body 都已持有许可（acquire 在 handle 之前）。 */
    private CountDownLatch saturate(IOExecutor ex, int n) throws Exception {
        CountDownLatch inside = new CountDownLatch(n);
        CountDownLatch release = new CountDownLatch(1);
        releases.add(release);
        for (int i = 0; i < n; i++) {
            ex.executeAdmitted(p -> {
                inside.countDown();
                try {
                    release.await(AWAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                p.tryComplete();
            });
        }
        assertTrue(inside.await(AWAIT_SECONDS, TimeUnit.SECONDS), n + " 个占位 body 必须全部进入");
        return release;
    }

    private void bootMycatCoreWith(MycatServerConfig config) throws Exception {
        Path home = Files.createTempDirectory("mycat-pr1-wiring-");
        tempHomes.add(home);
        String json = ConfigReaderWriter.getReaderWriterBySuffix("json").transformation(config);
        Files.write(home.resolve("server.json"), json.getBytes(StandardCharsets.UTF_8));
        System.setProperty("MYCAT_HOME", home.toString());
        System.setProperty("mode", "local");
        new MycatCore();
        openedVertx.add(MetaClusterCurrent.wrapper(Vertx.class));
    }

    /** 直接读生产 server.json，而不是手抄一份可能漂移的副本。 */
    private static MycatServerConfig productionServerConfig() throws Exception {
        try (InputStream in = MycatCore.class.getResourceAsStream("/server.json")) {
            assertTrue(in != null, "classpath 上必须有生产 server.json");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return ConfigReaderWriter.transformation("json", text, MycatServerConfig.class);
        }
    }

    private static byte[] sql(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertIoLimit(Throwable cause) {
        assertTrue(cause != null, "准入失败必须以异常完成 Future，实得 null");
        assertTrue(cause instanceof MycatException, "须是 MycatException，实得 " + cause);
        assertEquals(MycatErrorCode.ERR_IO_CONCURRENCY_LIMIT, ((MycatException) cause).getErrorCode(),
                "准入拒绝须报 MYCAT-3007");
    }

    private static Throwable awaitFailure(Future<?> future, long seconds) throws Exception {
        try {
            future.toCompletionStage().toCompletableFuture().get(seconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            return e.getCause();
        }
        return fail("期望失败，却成功了");
    }

    private static void settle(Future<?> future, long seconds) throws Exception {
        try {
            future.toCompletionStage().toCompletableFuture().get(seconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException ignored) {
            // 无元数据/后端时 body 自身失败属预期；本锁只关心它是否被闸放行进入 body
        }
    }

    private static void awaitCount(IOExecutor ex, long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (ex.count() != expected) {
            if (System.nanoTime() > deadline) {
                fail("等待 count()==" + expected + " 超时，实得 " + ex.count());
            }
            Thread.sleep(5);
        }
    }

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null) {
            if (Files.isRegularFile(p.resolve("common/src/main/java/io/mycat/IOExecutor.java"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new AssertionError("找不到仓库根，cwd=" + Paths.get("").toAbsolutePath());
    }

    private static List<Path> mainSources(Path root) throws Exception {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".java"))
                    .filter(f -> relative(root, f).contains("/src/main/java/"))
                    .filter(f -> !excluded(root, f))
                    .collect(Collectors.toList());
        }
    }

    /** 跳过 .git/.qoder/.mycat-home* 等隐藏目录与构建产物：它们不是被编译的生产源码。 */
    private static boolean excluded(Path root, Path file) {
        for (Path segment : root.relativize(file)) {
            String name = segment.toString();
            if (name.startsWith(".") || name.equals("target") || name.equals("node_modules")) {
                return true;
            }
        }
        return false;
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static String read(Path file) throws Exception {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static int count(String text, String token) {
        int n = 0;
        int i = text.indexOf(token);
        while (i >= 0) {
            n++;
            i = text.indexOf(token, i + token.length());
        }
        return n;
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(p);
            }
        }
    }
}