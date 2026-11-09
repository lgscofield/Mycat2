package io.mycat.exporter;

import io.mycat.MetaClusterCurrent;
import io.mycat.config.MycatServerConfig;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.hotspot.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Prometheus 抓取端点启动器。
 *
 * <p>MYCAT CHANGE (I-DEBT-95/G4):Prometheus 监控端点安全收尾。此前经
 * {@code HTTPServer(int port)} 通配绑定且完全无认证;现把“绑定面/认证”决策抽成
 * 可离线锁定的纯函数({@link #resolveBindAddress}/{@link #isAuthorized}),
 * 并在 {@code HTTPServer} 前置 Bearer 校验。行为矩阵:
 *
 * <pre>
 * | monitorToken(properties 或 -D) | prometheusBind | 绑定面            | 认证                                       |
 * |--------------------------------|----------------|-------------------|--------------------------------------------|
 * | 未配置                         | 任意(被忽略)  | 127.0.0.1(强制)  | 无认证,启动 WARN(仅回环兜底)            |
 * | 已配置                         | 未配置         | 127.0.0.1(缺省)  | Authorization: Bearer &lt;token&gt;,不匹配 401 |
 * | 已配置                         | 已配置         | prometheusBind    | 同上                                       |
 * | prometheusPort 未配置          | -              | 完全不起          | 完全不起(既有行为零变化)                 |
 * </pre>
 *
 * <p>monitorToken 解析优先级与 9066 监控面(I-DEBT-89/G4,{@code MycatSQLLogMonitorImpl})一致:
 * {@code -DmonitorToken} 优先于 server.json {@code properties.monitorToken}。
 * 决策锁见 {@code PrometheusExporterAuthTest}。
 */
public class PrometheusExporter implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(PrometheusExporter.class);

    /** 回环地址:无认证时的强制绑定面,有 token 但未配置 prometheusBind 时的缺省绑定面。 */
    static final String LOOPBACK_ADDRESS = "127.0.0.1";

    /** 无 monitorToken 时的 WARN 语义常量(锁于 PrometheusExporterAuthTest)。 */
    static final String NO_TOKEN_WARN_MESSAGE =
            "I-DEBT-95/G4: Prometheus 端点未配置 monitorToken,当前无认证,已强制仅绑定回环 127.0.0.1"
                    + "(配置 monitorToken 后可用 prometheusBind 扩大绑定面)";

    /**
     * -D 系统属性优先于 server.json properties(同 9066 monitorToken 语义)。
     */
    static String firstNonEmpty(String sysPropValue, String propertyValue) {
        if (sysPropValue != null && !sysPropValue.isEmpty()) return sysPropValue;
        return propertyValue == null ? "" : propertyValue;
    }

    /** 仅从 server.json properties 解析 monitorToken(纯函数,便于离线锁)。 */
    static String resolveMonitorTokenFromProperties(Map<?, ?> properties) {
        if (properties == null) return "";
        Object o = properties.get("monitorToken");
        return o == null ? "" : Objects.toString(o);
    }

    /**
     * 认证决策(纯函数):有 token → 必须精确 {@code Authorization: Bearer <token>}(大小写/空格敏感,
     * 与 9066 MycatSQLLogMonitorImpl 同语义);无 token → 无认证模式(绑定面已强制回环兜底)。
     */
    static boolean isAuthorized(String authorizationHeader, String expectedToken) {
        if (expectedToken == null || expectedToken.isEmpty()) return true;
        return ("Bearer " + expectedToken).equals(authorizationHeader);
    }

    /**
     * 绑定面决策(纯函数):无 token → 一律回环(prometheusBind 被忽略,防止无认证外绑);
     * 有 token → prometheusBind,缺省/空白回环。
     */
    static String resolveBindAddress(Map<?, ?> properties, String monitorToken) {
        if (monitorToken == null || monitorToken.isEmpty()) {
            return LOOPBACK_ADDRESS;
        }
        Object bind = properties == null ? null : properties.get("prometheusBind");
        if (bind == null) return LOOPBACK_ADDRESS;
        String bindAddress = Objects.toString(bind).trim();
        return bindAddress.isEmpty() ? LOOPBACK_ADDRESS : bindAddress;
    }

    @Override
    public void run() {
        MycatServerConfig mycatServerConfig = MetaClusterCurrent.wrapper(MycatServerConfig.class);
        Optional.ofNullable(mycatServerConfig.getProperties().getOrDefault("prometheusPort",null))
                .ifPresent(port->{
                    try {
                        // I-DEBT-95/G4:token 解析同 9066(-D 优先,server.json properties 兜底)
                        Map<String, Object> properties = mycatServerConfig.getProperties();
                        String monitorToken = firstNonEmpty(System.getProperty("monitorToken", ""),
                                resolveMonitorTokenFromProperties(properties));
                        String bindAddress = resolveBindAddress(properties, monitorToken);
                        if (monitorToken.isEmpty()) {
                            LOGGER.warn(NO_TOKEN_WARN_MESSAGE);
                        }
                        CollectorList collectorList = new CollectorList(
                                new StandardExports(),
                                new MemoryPoolsExports(),
                                new BufferPoolsExports(),
                                new GarbageCollectorExports(),
                                new ThreadExports(),
                                new ClassLoadingExports(),
                                new VersionInfoExports(),
                                //////////////////////////////////////////
                                new SqlStatCollector(),
                                new ReplicaCollector(),
                                new CPULoadCollector(),
                                /////////////////////////////////////////
                                new ConnectionCounterCollector(),
                                new HeartbeatCollector(),
                                new InstanceCollector(),
                                new ThreadPoolCollector()
                        );
                        collectorList.register();
                        InetSocketAddress socketAddress = new InetSocketAddress(
                                InetAddress.getByName(bindAddress), Integer.parseInt(Objects.toString(port)));
                        LOGGER.info("PrometheusExporter start server port:" + port
                                + ", bind:" + bindAddress
                                + (monitorToken.isEmpty() ? ", auth:off(loopback only)" : ", auth:bearer"));
                        HTTPServer server = new HTTPServer(socketAddress, CollectorRegistry.defaultRegistry, false,
                                monitorToken.isEmpty() ? null : monitorToken);
                        LOGGER.info("PrometheusExporter success");
                    } catch (Throwable e) {
                        LOGGER.error("", e);
                    }
                });
    }
}
