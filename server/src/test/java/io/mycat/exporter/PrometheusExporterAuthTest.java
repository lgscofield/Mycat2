package io.mycat.exporter;

import io.prometheus.client.CollectorRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Prometheus 抓取端点安全离线锁(I-DEBT-95/G4)。
 *
 * <p>锁两层:<b>决策纯函数矩阵</b>(不落盘、不依赖 Mycat 实例)与<b>回环活体自检</b>
 * (仅绑 127.0.0.1 临时端口,离线可跑)。被锁语义:
 * <ul>
 *   <li>有 monitorToken → 必须 {@code Authorization: Bearer <token>} 精确匹配(大小写、空格、缺scheme均拒),否则 401;</li>
 *   <li>无 monitorToken → 无认证,但强制绑定回环 {@code 127.0.0.1}(显式 prometheusBind 也被忽略)并 WARN;</li>
 *   <li>{@code prometheusPort} 未配置 → 完全不起(既有行为零变化,此处不锁——由 run() 的 Optional 门保证)。</li>
 * </ul>
 *
 * <p>行为矩阵(与 {@link PrometheusExporter} 类 javadoc 保持一致):
 * <pre>
 * | monitorToken | prometheusBind | 绑定面            | 认证                                   |
 * |--------------|----------------|-------------------|----------------------------------------|
 * | 未配置       | 任意(忽略)    | 127.0.0.1(强制)  | 无,启动时 WARN                        |
 * | 已配置       | 未配置         | 127.0.0.1(缺省)  | Bearer 不匹配 → 401                    |
 * | 已配置       | 已配置         | prometheusBind    | Bearer 不匹配 → 401                    |
 * </pre>
 */
class PrometheusExporterAuthTest {

    private static final String TOKEN = "s3cret";

    // ---------- isAuthorized 矩阵:有 token → 必须精确 "Bearer <token>" ----------

    @Test
    void withTokenExactBearerIsAccepted() {
        Assertions.assertTrue(PrometheusExporter.isAuthorized("Bearer " + TOKEN, TOKEN));
    }

    @Test
    void withTokenWrongValueIsRejected() {
        Assertions.assertFalse(PrometheusExporter.isAuthorized("Bearer wrong", TOKEN));
    }

    @Test
    void withTokenMissingHeaderIsRejected() {
        Assertions.assertFalse(PrometheusExporter.isAuthorized(null, TOKEN));
    }

    @Test
    void withTokenRawValueWithoutBearerSchemeIsRejected() {
        Assertions.assertFalse(PrometheusExporter.isAuthorized(TOKEN, TOKEN));
    }

    @Test
    void withTokenLowercaseSchemeIsRejected() {
        // 与 9066 MycatSQLLogMonitorImpl 的语义一致:精确前缀匹配,不宽容 scheme 大小写
        Assertions.assertFalse(PrometheusExporter.isAuthorized("bearer " + TOKEN, TOKEN));
    }

    @Test
    void withTokenExtraWhitespaceIsRejected() {
        Assertions.assertFalse(PrometheusExporter.isAuthorized("Bearer  " + TOKEN, TOKEN));
        Assertions.assertFalse(PrometheusExporter.isAuthorized("Bearer " + TOKEN + " ", TOKEN));
    }

    @Test
    void withEmptyTokenIsNoAuthMode() {
        // 无认证模式由“绑定面收窄到回环”兜底,而不是由 isAuthorized 拒绝请求
        Assertions.assertTrue(PrometheusExporter.isAuthorized(null, ""));
        Assertions.assertTrue(PrometheusExporter.isAuthorized(null, null));
    }

    // ---------- resolveBindAddress 矩阵 ----------

    @Test
    void noTokenForcesLoopbackEvenWhenBindConfigured() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("prometheusBind", "0.0.0.0");
        Assertions.assertEquals("127.0.0.1", PrometheusExporter.resolveBindAddress(properties, ""));
    }

    @Test
    void noTokenWithoutBindDefaultsLoopback() {
        Assertions.assertEquals("127.0.0.1", PrometheusExporter.resolveBindAddress(new HashMap<>(), ""));
        Assertions.assertEquals("127.0.0.1", PrometheusExporter.resolveBindAddress(null, null));
    }

    @Test
    void tokenWithoutBindDefaultsLoopback() {
        Assertions.assertEquals("127.0.0.1", PrometheusExporter.resolveBindAddress(new HashMap<>(), TOKEN));
    }

    @Test
    void tokenWithBindIsHonored() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("prometheusBind", "192.168.1.10");
        Assertions.assertEquals("192.168.1.10", PrometheusExporter.resolveBindAddress(properties, TOKEN));
    }

    @Test
    void tokenWithBlankBindFallsBackToLoopback() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("prometheusBind", "   ");
        Assertions.assertEquals("127.0.0.1", PrometheusExporter.resolveBindAddress(properties, TOKEN));
    }

    // ---------- WARN 语义常量与常量锁 ----------

    @Test
    void noTokenWarnCarriesNoAuthAndLoopbackSemantics() {
        String message = PrometheusExporter.NO_TOKEN_WARN_MESSAGE;
        Assertions.assertTrue(message.contains("无认证"), "WARN 必须声明无认证: " + message);
        Assertions.assertTrue(message.contains("回环"), "WARN 必须声明仅回环: " + message);
        Assertions.assertTrue(message.contains("monitorToken"), "WARN 必须指出补救键 monitorToken: " + message);
    }

    @Test
    void loopbackConstantIsLiteralLoopbackIp() {
        Assertions.assertEquals("127.0.0.1", PrometheusExporter.LOOPBACK_ADDRESS);
    }

    // ---------- token 解析优先级(同 I-DEBT-89/G4 9066 语义:-D 优先于 properties) ----------

    @Test
    void tokenResolvedFromProperties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("monitorToken", "abc");
        Assertions.assertEquals("abc", PrometheusExporter.resolveMonitorTokenFromProperties(properties));
        Assertions.assertEquals("", PrometheusExporter.resolveMonitorTokenFromProperties(new HashMap<>()));
        Assertions.assertEquals("", PrometheusExporter.resolveMonitorTokenFromProperties(null));
    }

    @Test
    void sysPropTokenTakesPrecedenceOverProperties() {
        Assertions.assertEquals("sys", PrometheusExporter.firstNonEmpty("sys", "prop"));
        Assertions.assertEquals("prop", PrometheusExporter.firstNonEmpty("", "prop"));
        Assertions.assertEquals("", PrometheusExporter.firstNonEmpty(null, null));
    }

    // ---------- 活体自检(离线:仅回环 + 临时端口) ----------

    @Test
    void liveServerWithoutTokenBindsLoopbackAndServesAnonymously() throws Exception {
        String bind = PrometheusExporter.resolveBindAddress(new HashMap<>(), "");
        HTTPServer server = new HTTPServer(
                new InetSocketAddress(InetAddress.getByName(bind), 0),
                CollectorRegistry.defaultRegistry, true, null);
        try {
            Assertions.assertTrue(server.server.getAddress().getAddress().isLoopbackAddress(),
                    "无 token 时必须绑定回环,实际: " + server.server.getAddress());
            Assertions.assertEquals(200, get(server.getPort(), null));
        } finally {
            server.stop();
        }
    }

    @Test
    void liveServerWithTokenRejectsAnonymousAndWrongTokenAcceptsBearer() throws Exception {
        String bind = PrometheusExporter.resolveBindAddress(new HashMap<>(), TOKEN);
        HTTPServer server = new HTTPServer(
                new InetSocketAddress(InetAddress.getByName(bind), 0),
                CollectorRegistry.defaultRegistry, true, TOKEN);
        try {
            int port = server.getPort();
            Assertions.assertEquals(401, get(port, null), "缺 Authorization 必须 401");
            Assertions.assertEquals(401, get(port, "Bearer wrong"), "错误 token 必须 401");
            Assertions.assertEquals(200, get(port, "Bearer " + TOKEN), "正确 Bearer 必须 200");
        } finally {
            server.stop();
        }
    }

    private static int get(int port, String authorization) throws IOException {
        HttpURLConnection connection = (HttpURLConnection)
                new URL("http://127.0.0.1:" + port + "/metrics").openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        if (authorization != null) {
            connection.setRequestProperty("Authorization", authorization);
        }
        try {
            int code = connection.getResponseCode();
            drain(connection);
            return code;
        } finally {
            connection.disconnect();
        }
    }

    private static void drain(HttpURLConnection connection) throws IOException {
        // 读完 body(错误响应体也读),避免 keep-alive 连接悬挂
        InputStream in = connection.getResponseCode() < 400 ? connection.getInputStream() : connection.getErrorStream();
        if (in == null) return;
        byte[] buffer = new byte[512];
        while (in.read(buffer) != -1) {
            // drain
        }
        in.close();
    }
}
