package io.mycat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.mycat.config.ClusterConfig;
import io.mycat.config.DatasourceConfig;
import io.mycat.config.LogicSchemaConfig;
import io.mycat.config.MycatRouterConfig;
import io.mycat.config.SequenceConfig;
import io.mycat.config.ServerConfig;
import io.mycat.config.SqlCacheConfig;
import io.mycat.config.UserConfig;
import io.mycat.datasource.jdbc.DruidDatasourceProvider;
import io.mycat.datasource.jdbc.datasource.JdbcConnectionManager;
import io.mycat.plug.loadBalance.LoadBalanceManager;
import io.mycat.replica.ReplicaSelector;
import io.mycat.replica.ReplicaSelectorManager;
import io.mycat.replica.ReplicaSelectorRuntime;
import io.mycat.sqlhandler.ConfigUpdater;
import io.mycat.sqlhandler.config.StorageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-DEBT-98 回归锁：孤儿配置致启动崩溃（运维事故级）。
 *
 * <p>
 * 活体证据：audit/2026-11-08-capability-audit/logs/E24-boot8068.log——集群文件 c0/c1 持久化引用
 * dw0/dw1/dr0/dr1，对应数据源文件被清理后重启即 {@code IllegalArgumentException: ignored dw0}
 * （ReplicaSelectorRuntime.registerDatasource 抛出 ← MycatRouterConfigOps.commit:527 ←
 * ConfigUpdater.load:60 ← loadConfigFromFile:41 ← MycatCore.startServer:237），整机无法重启；
 * 手工删除孤儿文件后启动才恢复（E25）。
 *
 * <p>
 * 拍板语义（勿改）：孤儿引用（集群文件存在但被引用的 target 数据源缺失）= 启动时
 * <b>WARN + 跳过该孤儿条目 + 继续启动</b>（自愈式），不再抛 IllegalArgumentException；
 * 同批多个孤儿逐条 WARN。自愈：全孤儿集群文件移入 {@code orphan/} 子目录并落盘记录；
 * 部分孤儿引用仅告警、文件保留（其余有效引用继续可用）。对照：无孤儿时行为零变化。
 *
 * <p>
 * 本测试完全离线：临时目录即 mycat-home，数据源 URL 不发起任何连接（Druid 池惰性建连，
 * E24 已证启动路径对未连通后端安全）。
 */
public class ConfigUpdaterOrphanConfigTest {

    @TempDir
    Path home;

    private Map<Class, Object> savedContext;
    private final List<Logger> attachedLoggers = new ArrayList<>();
    private final List<ListAppender<ILoggingEvent>> attachedAppenders = new ArrayList<>();

    @BeforeEach
    void saveAndSetupContext() {
        savedContext = MetaClusterCurrent.copyContext();
        StorageManager storageManager = ConfigUpdater.createStorageManager(home.toAbsolutePath());
        for (Class aClass : Arrays.asList(LogicSchemaConfig.class,
                ClusterConfig.class,
                DatasourceConfig.class,
                UserConfig.class,
                SequenceConfig.class,
                SqlCacheConfig.class)) {
            storageManager.register(aClass);
        }
        Map<Class, Object> context = new HashMap<>();
        context.put(StorageManager.class, storageManager);
        context.put(ServerConfig.class, new ServerConfig());
        context.put(LoadBalanceManager.class, new LoadBalanceManager());
        // 离线锁的关键拼图：预注册 MysqlVariableService，commit() 走 borrow 分支跳过
        // "连原型库拉 SHOW GLOBAL VARIABLES"（getMysqlVariableService 首行 exist→borrow）。
        // 空连接池管理器→containsKey(prototype)=false→MysqlVariableServiceImpl 落 I-DEBT-86
        // 内置默认变量表，全程零后端连接。生产语义等价：MysqlVariableService 在场即 borrow。
        JdbcConnectionManager emptyConnectionManager =
                new JdbcConnectionManager(DruidDatasourceProvider.class.getCanonicalName(), new HashMap<>());
        context.put(io.mycat.MysqlVariableService.class, new io.mycat.MysqlVariableServiceImpl(emptyConnectionManager));
        MetaClusterCurrent.register(context);

        attachLogCapture(ConfigUpdater.class);
        attachLogCapture(ReplicaSelectorRuntime.class);
    }

    @AfterEach
    void restoreContextAndDetachLoggers() {
        for (int i = 0; i < attachedLoggers.size(); i++) {
            attachedLoggers.get(i).detachAppender(attachedAppenders.get(i));
        }
        attachedLoggers.clear();
        attachedAppenders.clear();
        MetaClusterCurrent.register(savedContext);
    }

    ////////////////////////////////////////活体场景复现（E24）////////////////////////////////////////

    /**
     * E24 活体场景：集群文件 c0(masters=[dw0],replicas=[dr0])、c1(masters=[dw1]) 存在，
     * 但对应数据源文件已被清理 → 启动不得再抛 IllegalArgumentException；孤儿集群被跳过
     * （不进入运行时配置与 replica 运行时），正常条目（prototype 集群 + prototypeDs 数据源）
     * 全部加载；每个孤儿引用逐条 WARN；全孤儿集群文件自愈移入 orphan/ 并落盘记录。
     */
    @Test
    public void orphanDatasourceReferencesStartupContinuesAndQuarantinesOrphanFiles() throws Exception {
        writeDatasourceFile("prototypeDs");
        writeClusterFile("prototype", Collections.singletonList("prototypeDs"), Collections.emptyList());
        writeClusterFile("c0", Collections.singletonList("dw0"), Collections.singletonList("dr0"));
        writeClusterFile("c1", Collections.singletonList("dw1"), Collections.emptyList());

        assertDoesNotThrow(() -> ConfigUpdater.loadConfigFromFile(),
                "孤儿数据源引用不得阻断启动（E24 运维事故回归锁）");

        // 孤儿条目被跳过：不进入运行时配置
        MycatRouterConfig runtimeConfig = MetaClusterCurrent.wrapper(MycatRouterConfig.class);
        List<String> loadedClusterNames = runtimeConfig.getClusters().stream()
                .map(ClusterConfig::getName).collect(Collectors.toList());
        assertTrue(loadedClusterNames.contains("prototype"), "正常集群必须照常加载，实际=" + loadedClusterNames);
        assertFalse(loadedClusterNames.contains("c0"), "全孤儿集群 c0 应被跳过，实际=" + loadedClusterNames);
        assertFalse(loadedClusterNames.contains("c1"), "全孤儿集群 c1 应被跳过，实际=" + loadedClusterNames);
        List<String> loadedDatasourceNames = runtimeConfig.getDatasources().stream()
                .map(DatasourceConfig::getName).collect(Collectors.toList());
        assertTrue(loadedDatasourceNames.contains("prototypeDs"), "正常数据源必须照常加载，实际=" + loadedDatasourceNames);
        assertFalse(loadedDatasourceNames.contains("dw0"), "孤儿数据源不存在于文件层，自然不得加载");

        // 运行时 replica 面：prototype 可用，c0/c1 不注册
        ReplicaSelectorManager replicaSelectorManager =
                MetaClusterCurrent.wrapper(ReplicaSelectorManager.class);
        assertTrue(replicaSelectorManager.isReplicaName("prototype"));
        assertFalse(replicaSelectorManager.isReplicaName("c0"), "全孤儿集群不得注册进运行时");
        assertFalse(replicaSelectorManager.isReplicaName("c1"), "全孤儿集群不得注册进运行时");
        assertTrue(replicaSelectorManager.isDatasource("prototypeDs"));
        assertFalse(replicaSelectorManager.isDatasource("dw0"), "孤儿数据源不得注册进运行时");

        // 同批多个孤儿逐条 WARN（每个孤儿引用一条，含孤儿名）
        List<String> warnings = warnMessages();
        assertTrue(warnings.stream().anyMatch(m -> m.contains("dw0")),
                "孤儿引用 dw0 必须逐条 WARN，实际 WARN=" + warnings);
        assertTrue(warnings.stream().anyMatch(m -> m.contains("dr0")),
                "孤儿引用 dr0 必须逐条 WARN，实际 WARN=" + warnings);
        assertTrue(warnings.stream().anyMatch(m -> m.contains("dw1")),
                "孤儿引用 dw1 必须逐条 WARN，实际 WARN=" + warnings);

        // 自愈：全孤儿集群文件移入 orphan/ 子目录并落盘记录；正常文件原地不动
        Path orphanDir = home.resolve("orphan");
        assertTrue(Files.isDirectory(orphanDir), "自愈目录 orphan/ 必须存在");
        List<String> orphanDirFiles = listFileNames(orphanDir);
        assertTrue(orphanDirFiles.stream().anyMatch(n -> n.startsWith("c0.cluster.json")),
                "c0 孤儿文件应被隔离到 orphan/，实际=" + orphanDirFiles);
        assertTrue(orphanDirFiles.stream().anyMatch(n -> n.startsWith("c1.cluster.json")),
                "c1 孤儿文件应被隔离到 orphan/，实际=" + orphanDirFiles);
        assertTrue(listFileNames(home.resolve("clusters")).contains("prototype.cluster.json"),
                "正常集群文件必须原地保留");
        assertTrue(listFileNames(home.resolve("datasources")).contains("prototypeDs.datasource.json"),
                "正常数据源文件必须原地保留");
        assertFalse(listFileNames(home.resolve("clusters")).contains("c0.cluster.json"),
                "孤儿文件隔离后不得留在 clusters/ 继续毒化下次启动");

        Path record = orphanDir.resolve("orphan-record.jsonl");
        assertTrue(Files.isRegularFile(record), "自愈必须落盘记录 orphan/orphan-record.jsonl");
        String recordText = new String(Files.readAllBytes(record), StandardCharsets.UTF_8);
        assertTrue(recordText.contains("c0"), "自愈记录必须含孤儿集群名 c0：" + recordText);
        assertTrue(recordText.contains("dw0"), "自愈记录必须含缺失引用名 dw0：" + recordText);
    }

    /**
     * 极端档：只有孤儿集群文件、数据源目录整个缺失（全局数据源映射为空）——
     * 同样不得崩（旧码在此路径先撞 assert/NoSuchElementException，后撞 ignored 抛出）。
     */
    @Test
    public void orphanClusterWithoutAnyDatasourceFilesStartupContinues() throws Exception {
        writeClusterFile("c0", Collections.singletonList("dw0"), Collections.emptyList());

        assertDoesNotThrow(() -> ConfigUpdater.loadConfigFromFile(),
                "数据源全缺时集群引用也不得阻断启动（I-DEBT-98）");

        MycatRouterConfig runtimeConfig = MetaClusterCurrent.wrapper(MycatRouterConfig.class);
        List<String> loadedClusterNames = runtimeConfig.getClusters().stream()
                .map(ClusterConfig::getName).collect(Collectors.toList());
        assertFalse(loadedClusterNames.contains("c0"), "全孤儿集群应被跳过，实际=" + loadedClusterNames);
        assertTrue(warnMessages().stream().anyMatch(m -> m.contains("dw0")),
                "孤儿引用 dw0 必须 WARN，实际 WARN=" + warnMessages());
        assertTrue(Files.isDirectory(home.resolve("orphan")), "孤儿文件应被隔离到 orphan/");
        assertFalse(Files.exists(home.resolve("clusters").resolve("c0.cluster.json")),
                "孤儿文件隔离后不得留在 clusters/");
    }

    ////////////////////////////////////////引用粒度：部分孤儿////////////////////////////////////////

    /**
     * 引用粒度锁：集群 c2 引用 [dw0(缺失), prototypeDs(有效)]——只跳过孤儿引用 dw0，
     * 保留有效引用 prototypeDs；集群条目本身保留注册。该路径不经过文件层
     * （热重载/内存配置同源），运行态跳过是文件层自愈失效时的最后防线。
     */
    @Test
    public void partialOrphanReferenceSkipsOnlyMissingAndKeepsValid() {
        DatasourceConfig prototypeDs = datasource("prototypeDs");
        Map<String, DatasourceConfig> datasources = new HashMap<>();
        datasources.put(prototypeDs.getName(), prototypeDs);

        ClusterConfig c2 = cluster("c2", Arrays.asList("dw0", "prototypeDs"));
        List<ClusterConfig> clusters = Collections.singletonList(c2);

        ReplicaSelectorRuntime runtime = assertDoesNotThrow(
                () -> new ReplicaSelectorRuntime(clusters, datasources,
                        new LoadBalanceManager(), name -> 0,
                        (command, initialDelay, period, unit) -> () -> {
                        }),
                "部分孤儿引用不得抛 IllegalArgumentException（I-DEBT-98 拍板语义）");

        assertTrue(runtime.isReplicaName("c2"), "部分孤儿的集群本身必须照常注册");
        Map<String, ?> raw = runtime.getReplicaMap().get("c2").getRawDataSourceMap();
        assertTrue(raw.containsKey("prototypeDs"), "有效引用必须保留注册，实际=" + raw.keySet());
        assertFalse(raw.containsKey("dw0"), "孤儿引用必须被跳过，实际=" + raw.keySet());

        List<String> warnings = warnMessages();
        assertTrue(warnings.stream().anyMatch(m -> m.contains("dw0")),
                "孤儿引用 dw0 必须 WARN，实际 WARN=" + warnings);
        assertFalse(warnings.stream().anyMatch(m -> m.contains("prototypeDs")),
                "有效引用不得被误告警为孤儿");

        // 配置列表零变化：跳过孤儿引用不得改写集群配置（保护 commit 的 getConfig 一致性守卫）
        assertTrue(runtime.getConfig() == clusters || runtime.getConfig().containsAll(clusters),
                "跳过孤儿引用不得改写传入的集群配置列表");

        // 心跳流防线：孤儿引用不建心跳流（DefaultHeartbeatFlow 直取 instance.isMaster()，
        // 对未注册实例建流会在心跳状态同步时 NPE）；有效引用照常建流。
        runtime.putHeartFlow("c2", "dw0", strategy -> {
        });
        assertFalse(runtime.getHeartbeatDetectorMap().containsKey("c2.dw0"),
                "孤儿引用不得注册心跳流（防 instance NPE），实际=" + runtime.getHeartbeatDetectorMap().keySet());
        runtime.putHeartFlow("c2", "prototypeDs", strategy -> {
        });
        assertTrue(runtime.getHeartbeatDetectorMap().containsKey("c2.prototypeDs"),
                "有效引用必须照常注册心跳流，实际=" + runtime.getHeartbeatDetectorMap().keySet());
    }

    ////////////////////////////////////////对照：无孤儿零变化////////////////////////////////////////

    /**
     * 对照锁：无孤儿时行为零变化——正常加载、无 orphan/ 自愈产物、无孤儿 WARN、文件原地不动。
     */
    @Test
    public void healthyConfigLoadsUnchangedWithoutQuarantine() throws Exception {
        writeDatasourceFile("prototypeDs");
        writeClusterFile("prototype", Collections.singletonList("prototypeDs"), Collections.emptyList());

        assertDoesNotThrow(() -> ConfigUpdater.loadConfigFromFile());

        MycatRouterConfig runtimeConfig = MetaClusterCurrent.wrapper(MycatRouterConfig.class);
        List<String> loadedClusterNames = runtimeConfig.getClusters().stream()
                .map(ClusterConfig::getName).collect(Collectors.toList());
        List<String> loadedDatasourceNames = runtimeConfig.getDatasources().stream()
                .map(DatasourceConfig::getName).collect(Collectors.toList());
        assertTrue(loadedClusterNames.contains("prototype"), "正常集群必须加载");
        assertTrue(loadedDatasourceNames.contains("prototypeDs"), "正常数据源必须加载");

        assertFalse(Files.exists(home.resolve("orphan")), "无孤儿时不得产生自愈目录");
        assertTrue(listFileNames(home.resolve("clusters")).contains("prototype.cluster.json"),
                "无孤儿时配置文件必须原地保留");
        assertTrue(warnMessages().stream().noneMatch(m -> m.contains("orphan")),
                "无孤儿时不得出现孤儿告警，实际 WARN=" + warnMessages());
    }

    ////////////////////////////////////////helpers//////////////////////////////////////////////////

    private void attachLogCapture(Class<?> loggerOwner) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerOwner);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        attachedLoggers.add(logger);
        attachedAppenders.add(appender);
    }

    private List<String> warnMessages() {
        return attachedAppenders.stream()
                .flatMap(a -> a.list.stream())
                .filter(e -> e.getLevel() == Level.WARN || e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
    }

    private void writeDatasourceFile(String name) throws IOException {
        String json = "{\n" +
                "  \"name\" : \"" + name + "\",\n" +
                "  \"user\" : \"root\",\n" +
                "  \"password\" : \"123456\",\n" +
                "  \"maxCon\" : 1000,\n" +
                "  \"minCon\" : 1,\n" +
                "  \"maxRetryCount\" : 5,\n" +
                "  \"maxConnectTimeout\" : 30000,\n" +
                "  \"dbType\" : \"mysql\",\n" +
                // 死端口（33061）：离线锁不发真实连接；仅启动期 DbPlanManagerPersistorImpl.checkStore
                // 的一次性探针会触达（其内部 catch 落日志，connection refused 快速失败）。
                "  \"url\" : \"jdbc:mysql://127.0.0.1:33061/mysql?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&autoReconnect=true&useSSL=false\",\n" +
                "  \"weight\" : 0,\n" +
                "  \"initSqls\" : [ ],\n" +
                "  \"initSqlsGetConnection\" : true,\n" +
                "  \"instanceType\" : \"READ_WRITE\",\n" +
                "  \"idleTimeout\" : 60000,\n" +
                "  \"jdbcDriverClass\" : null,\n" +
                "  \"type\" : \"JDBC\",\n" +
                "  \"queryTimeout\" : 0,\n" +
                "  \"statementTimeout\" : 30000,\n" +
                "  \"removeAbandoned\" : false,\n" +
                "  \"removeAbandonedTimeoutSecond\" : 180,\n" +
                "  \"logAbandoned\" : true\n" +
                "}";
        Path datasources = home.resolve("datasources");
        Files.createDirectories(datasources);
        Files.write(datasources.resolve(name + ".datasource.json"), json.getBytes(StandardCharsets.UTF_8));
    }

    private void writeClusterFile(String name, List<String> masters, List<String> replicas) throws IOException {
        // heartbeat/timer 置 null：离线锁零后台流（putHeartFlow/start 对 null 心跳与 null timer 全跳过）
        String json = "{\n" +
                "  \"clusterType\" : \"MASTER_SLAVE\",\n" +
                "  \"switchType\" : \"SWITCH\",\n" +
                "  \"readBalanceType\" : \"BALANCE_ALL\",\n" +
                "  \"name\" : \"" + name + "\",\n" +
                "  \"readBalanceName\" : null,\n" +
                "  \"writeBalanceName\" : null,\n" +
                "  \"masters\" : " + toJsonList(masters) + ",\n" +
                "  \"replicas\" : " + toJsonList(replicas) + ",\n" +
                "  \"heartbeat\" : null,\n" +
                "  \"maxCon\" : 2000,\n" +
                "  \"timer\" : null\n" +
                "}";
        Path clusters = home.resolve("clusters");
        Files.createDirectories(clusters);
        Files.write(clusters.resolve(name + ".cluster.json"), json.getBytes(StandardCharsets.UTF_8));
    }

    private String toJsonList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[ ]";
        }
        return values.stream().collect(Collectors.joining("\",\"", "[\"", "\"]"));
    }

    private List<String> listFileNames(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return Collections.emptyList();
        }
        return Files.list(dir).map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
    }

    private static DatasourceConfig datasource(String name) {
        DatasourceConfig datasourceConfig = new DatasourceConfig();
        datasourceConfig.setName(name);
        datasourceConfig.setUser("root");
        datasourceConfig.setPassword("123456");
        datasourceConfig.setDbType("mysql");
        datasourceConfig.setUrl("jdbc:mysql://127.0.0.1:3306/mysql?useSSL=false");
        datasourceConfig.setInstanceType("READ_WRITE");
        return datasourceConfig;
    }

    private static ClusterConfig cluster(String name, List<String> masters) {
        ClusterConfig clusterConfig = new ClusterConfig();
        clusterConfig.setName(name);
        clusterConfig.setMasters(new ArrayList<>(masters));
        clusterConfig.setReplicas(new ArrayList<>());
        return clusterConfig;
    }
}
